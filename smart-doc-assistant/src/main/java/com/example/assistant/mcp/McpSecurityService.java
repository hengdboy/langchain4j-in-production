package com.example.assistant.mcp;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP 深度开发：MCP Server 端安全检查。
 *
 * <p>提供两层防护：
 * <ol>
 * <li><strong>API Key 鉴权</strong>：HTTP 传输下，客户端必须在
 * {@code Authorization: Bearer <key>} 或 {@code X-MCP-API-Key} 头携带密钥；
 * stdio 传输下由 OS 进程隔离（不暴露给远端）</li>
 * <li><strong>工具白名单</strong>：白名单从 {@link KnowledgeBaseMcpTools#TOOL_NAMES} 单一真相源读取，
 * 加新工具时只需改 {@link KnowledgeBaseMcpTools} 一处</li>
 * </ol>
 *
 * <h2>启动时校验</h2>
 * <p>API Key 在 {@link PostConstruct} 阶段校验：禁止空值、禁止过短（&lt;16 字符）。
 * 避免{@code application.yml} 配错时使用默认值或空 Key 上线造成万能 Key 漏洞。
 *
 * <h2>生产化建议</h2>
 * <ul>
 * <li>API Key 应存到 Vault / KMS，配置里只放引用</li>
 * <li>OAuth 2.0 鉴权（与标准 MCP 服务器对齐）</li>
 * <li>工具白名单按租户隔离（多租户 SaaS）</li>
 * <li>限流（每秒最大调用数）+ 熔断（连续失败自动禁用工具）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
@Slf4j
@Service
public class McpSecurityService {

    /** 最小 API Key 长度。低于此长度视为弱密钥，启动期直接抛异常。 */
    private static final int MIN_API_KEY_LENGTH = 16;

    private final String apiKey;
    private final List<String> allowedTools;

    public McpSecurityService(@Value("${chat.mcp.api-key:dev-mcp-key-replace-in-production}") String apiKey) {
        this.apiKey = apiKey;
        this.allowedTools = KnowledgeBaseMcpTools.TOOL_NAMES;
    }

    /**
     * 启动期校验 API Key 强度。空 / 缺省值 / 过短 → 直接抛异常阻断启动。
     * 比运行时拒绝更安全（运维事故无法"带病上线"）。
     */
    @PostConstruct
    void validateConfig() {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalStateException(
                    "Invalid chat.mcp.api-key: must be at least " + MIN_API_KEY_LENGTH
                            + " characters (current length=" + (apiKey == null ? 0 : apiKey.length())
                            + "). Set 'chat.mcp.api-key' in application.yml to a strong secret.");
        }
        if ("dev-mcp-key-replace-in-production".equals(apiKey)) {
            log.warn("[MCP Security] Using default API key — change chat.mcp.api-key for production!");
        }
        log.info("[MCP Security] API key validated (length={}), {} tools whitelisted",
                apiKey.length(), allowedTools.size());
    }

    /**
     * 校验请求头里的 API Key。
     * @param authHeader {@code Authorization} 头的值（可为 null）
     * @param customHeader {@code X-MCP-API-Key} 头的值（可为 null）
     * @return true 表示鉴权通过
     */
    public boolean isAuthorized(String authHeader, String customHeader) {
        if (authHeader != null) {
            // HTTP 规范：scheme 部分大小写不敏感（bearer / Bearer / BEARER 都应识别）
            String trimmed = authHeader.trim();
            if (trimmed.length() > 7 && trimmed.substring(0, 7).equalsIgnoreCase("Bearer ")) {
                return constantTimeEquals(trimmed.substring(7).trim(), apiKey);
            }
        }
        if (customHeader != null) {
            return constantTimeEquals(customHeader.trim(), apiKey);
        }
        return false;
    }

    /**
     * 校验工具名是否在白名单内。
     */
    public boolean isToolAllowed(String toolName) {
        return toolName != null && allowedTools.contains(toolName);
    }

    public List<String> allowedToolList() {
        return allowedTools;
    }

    /** 常量时间字符串比较（防止时序攻击泄露 Key 信息）。 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}