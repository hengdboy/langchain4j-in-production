package com.example.assistant.guardrail;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guardrails：<strong>工具层</strong>授权与限流（呼应  的错误处理 +  的工具白名单）。
 *
 * <p>三道防线：
 * <ol>
 * <li><strong>黑名单</strong>：永远禁止的工具（{@code blockedTools}，如 rm -rf）</li>
 * <li><strong>角色白名单</strong>：每个角色（user / admin / readonly）有允许的工具集</li>
 * <li><strong>速率限制</strong>：每会话每分钟最多 N 次（避免恶意调用 / 烧 token）</li>
 * </ol>
 *
 * <h2>与 Ch19 的工具白名单区别</h2>
 * <ul>
 * <li> 的白名单：MCP 服务端控制哪些工具暴露给所有客户端（粗粒度）</li>
 * <li>本类的角色白名单：单个会话内控制哪些角色能用哪些工具（细粒度 RBAC）</li>
 * <li>两者是<strong>叠加</strong>的——MCP 白名单 ⊇ 角色白名单 ⊇ 单次调用权限</li>
 * </ul>
 *
 * @author hengdboy
 * @since v0.18
 */
@Slf4j
@Component
public class ToolGuard {

    private final GuardrailProperties properties;
    private final GuardrailMetrics metrics;

    /** 会话 → [token bucket 上次刷新时间, 当前计数] —— 简化版限流器 */
    private final ConcurrentHashMap<String, long[]> rateLimitBuckets = new ConcurrentHashMap<>();

    public ToolGuard(GuardrailProperties properties, GuardrailMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 工具调用前授权检查。
     *
     * @param toolName   工具名
     * @param role       调用者角色（user / admin / readonly）
     * @param sessionId  会话 ID（限流的 key）
     * @throws GuardrailViolation 黑名单命中 / 角色无权 / 速率超限
     */
    public void authorize(String toolName, String role, String sessionId) {
        if (!properties.getTool().isEnabled()) {
            return;
        }
        checkBlacklist(toolName);
        checkRateLimit(sessionId);
        checkRolePermission(toolName, role);
    }

    /**
     * 角色白名单判定（不抛异常、不计限流）——暴露层 {@code TenantToolFilter} 与执行层
     * {@link #authorize} 共用同一份 {@code role-allowed-tools}。
     *
     * <p>暴露层如果自己另写一套规则，就会出现"模型看得见、一调就被拦"的白跑一轮；
     * 这里把判定抽成单一入口，暴露层用它筛，执行层用它兜底。
     *
     * <p>语义与 {@link #authorize} 一致：工具层关闭或整张表未配置 → 不限；
     * 角色未配置（deny by default）→ 拒绝；角色配置空列表 → 该角色不限制；
     * 否则工具必须在该角色列表内。
     */
    public boolean isToolAllowedForRole(String toolName, String role) {
        if (!properties.getTool().isEnabled()) {
            return true;
        }
        Map<String, List<String>> roleMap = properties.getTool().getRoleAllowedTools();
        if (roleMap == null || roleMap.isEmpty()) {
            return true;
        }
        List<String> allowed = roleMap.get(normalizeRole(role));
        if (allowed == null) {
            return false;
        }
        return allowed.isEmpty() || allowed.contains(toolName);
    }

    /** 黑名单：永远禁止。 */
    private void checkBlacklist(String toolName) {
        List<String> blocked = properties.getTool().getBlockedTools();
        if (blocked != null && blocked.contains(toolName)) {
            metrics.incrementToolBlock(toolName);
            log.warn("[ToolGuard] tool blocked by blacklist: {}", toolName);
            throw new GuardrailViolation("tool.blacklisted",
                    "工具 [" + toolName + "] 已被系统禁用");
        }
    }

    /**
     * 角色白名单：整张表为空或该角色的列表为空表示"不限制"；
     * 角色未配置为 deny by default；否则该角色只能用列表内的工具。
     */
    private void checkRolePermission(String toolName, String role) {
        if (isToolAllowedForRole(toolName, role)) {
            return;
        }
        // 能走到这里说明表非空且拒绝：再区分"角色没配置"与"工具不在列表"两种原因
        List<String> allowed = properties.getTool().getRoleAllowedTools().get(normalizeRole(role));
        if (allowed == null) {
            log.warn("[ToolGuard] role '{}' has no allowed-tools entry (deny by default)", role);
            metrics.incrementToolBlock(toolName);
            throw new GuardrailViolation("tool.role_unknown",
                    "角色 [" + role + "] 无可用工具权限");
        }
        log.warn("[ToolGuard] tool '{}' not in role '{}' whitelist ({})",
                toolName, role, allowed);
        metrics.incrementToolBlock(toolName);
        throw new GuardrailViolation("tool.not_in_role_whitelist",
                "角色 [" + role + "] 无权调用工具 [" + toolName + "]");
    }

    /** 防御性归一化：role 可能来自 JWT/网关头/默认值，统一转小写再匹配 yml 的小写键。 */
    private static String normalizeRole(String role) {
        return (role == null || role.isBlank()) ? "user" : role.toLowerCase(Locale.ROOT);
    }

    /**
     * 速率限制：每会话每分钟 N 次——滑动窗口式简化版。
     * 第一个元素：桶起始时间（毫秒）；第二个元素：当前计数。
     */
    private void checkRateLimit(String sessionId) {
        int limit = properties.getTool().getRateLimitPerMinute();
        if (limit <= 0) return;  // 0 或负数表示不限流

        long now = System.currentTimeMillis();
        long[] bucket = rateLimitBuckets.computeIfAbsent(sessionId == null ? "_anonymous" : sessionId,
                k -> new long[]{now, 0});

        // synchronized 简单锁：单会话内串行更新足够（实际生产用 AtomicLong 或限流库如 Resilience4j）
        synchronized (bucket) {
            if (now - bucket[0] >= 60_000) {
                bucket[0] = now;
                bucket[1] = 0;
            }
            bucket[1]++;
            if (bucket[1] > limit) {
                metrics.incrementRateLimit();
                log.warn("[ToolGuard] rate limit hit for session {}: {}/min", sessionId, limit);
                throw new GuardrailViolation("tool.rate_limited",
                        "调用频率超限（" + limit + "/分钟），请稍后再试");
            }
        }
    }
}
