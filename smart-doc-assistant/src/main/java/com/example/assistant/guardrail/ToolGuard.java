package com.example.assistant.guardrail;

import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ch21 Guardrails：<strong>工具层</strong>授权与限流（呼应 Ch16 的错误处理 + Ch19 的工具白名单）。
 *
 * <p>三道防线：
 * <ol>
 *   <li><strong>黑名单</strong>：永远禁止的工具（{@code blockedTools}，如 rm -rf）</li>
 *   <li><strong>角色白名单</strong>：每个角色（user / admin / readonly）有允许的工具集</li>
 *   <li><strong>速率限制</strong>：每会话每分钟最多 N 次（避免恶意调用 / 烧 token）</li>
 * </ol>
 *
 * <h2>与 Ch19 的工具白名单区别</h2>
 * <ul>
 *   <li>Ch19 的白名单：MCP 服务端控制哪些工具暴露给所有客户端（粗粒度）</li>
 *   <li>本类的角色白名单：单个会话内控制哪些角色能用哪些工具（细粒度 RBAC）</li>
 *   <li>两者是<strong>叠加</strong>的——MCP 白名单 ⊇ 角色白名单 ⊇ 单次调用权限</li>
 * </ul>
 *
 * @author langchain4j-tutorial
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

    /** 黑名单：永远禁止。 */
    private void checkBlacklist(String toolName) {
        List<String> blocked = properties.getTool().getBlockedTools();
        if (blocked != null && blocked.contains(toolName)) {
            metrics.incrementToolBlock(toolName);
            log.warn("🚫 [ToolGuard] tool blocked by blacklist: {}", toolName);
            throw new GuardrailViolation("tool.blacklisted",
                    "工具 [" + toolName + "] 已被系统禁用");
        }
    }

    /**
     * 角色白名单：roleAllowedTools 为空表示"该角色无限制"；
     * 否则该角色只能用列表内的工具。
     */
    private void checkRolePermission(String toolName, String role) {
        Map<String, List<String>> roleMap = properties.getTool().getRoleAllowedTools();
        if (roleMap == null || roleMap.isEmpty()) {
            return;
        }
        List<String> allowed = roleMap.get(role);
        if (allowed == null) {
            log.warn("🚫 [ToolGuard] role '{}' has no allowed-tools entry (deny by default)", role);
            metrics.incrementToolBlock(toolName);
            throw new GuardrailViolation("tool.role_unknown",
                    "角色 [" + role + "] 无可用工具权限");
        }
        if (!allowed.contains(toolName)) {
            log.warn("🚫 [ToolGuard] tool '{}' not in role '{}' whitelist ({})",
                    toolName, role, allowed);
            metrics.incrementToolBlock(toolName);
            throw new GuardrailViolation("tool.not_in_role_whitelist",
                    "角色 [" + role + "] 无权调用工具 [" + toolName + "]");
        }
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
                log.warn("🚫 [ToolGuard] rate limit hit for session {}: {}/min", sessionId, limit);
                throw new GuardrailViolation("tool.rate_limited",
                        "调用频率超限（" + limit + "/分钟），请稍后再试");
            }
        }
    }
}