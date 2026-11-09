package com.example.assistant.guardrail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Guardrails：三层配置（input / output / tool），每层独立开关。
 *
 * <p>Spring Boot {@code @ConfigurationProperties} 绑定 {@code chat.guardrail.*} 配置项；
 * 任何字段都可独立关闭，便于灰度与排错。
 *
 * <h2>配置示例（application.yml）</h2>
 * <pre>
 * chat:
 * guardrail:
 * enabled: true
 * input:
 * enabled: true
 * strip-html: true
 * strip-control-chars: true
 * compress-blank: true
 * max-length: 4000
 * output:
 * enabled: true
 * redact-phone: true
 * redact-email: true
 * redact-id-card: true
 * redact-bank-card: true
 * block-api-key: true
 * tool:
 * enabled: true
 * default-role: user
 * rate-limit-per-minute: 60
 * blocked-tools: [executeShell, formatDisk]
 * </pre>
 *
 * @author hengdboy
 * @since v0.18
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chat.guardrail")
public class GuardrailProperties {

    /** 全局开关（默认 true）。false 时所有层关闭，保留 application-local 调测能力。 */
    private boolean enabled = true;

    private InputConfig input = new InputConfig();
    private OutputConfig output = new OutputConfig();
    private ToolConfig tool = new ToolConfig();

    @Getter @Setter
    public static class InputConfig {
        /** 是否启用输入层（Controller 接收 → AiService 调用前的预处理） */
        private boolean enabled = true;
        /** 去除 HTML 标签（防 XSS / 注入载体） */
        private boolean stripHtml = true;
        /** 去除控制字符（保留换行 / 回车 / 制表符） */
        private boolean stripControlChars = true;
        /** 压缩连续空白（保留语义可读性） */
        private boolean compressBlank = true;
        /** 单次输入最大长度（超过截断 + ...[truncated]） */
        private int maxLength = 4000;
    }

    @Getter @Setter
    public static class OutputConfig {
        private boolean enabled = true;
        private boolean redactPhone = true;
        private boolean redactEmail = true;
        private boolean redactIdCard = true;
        private boolean redactBankCard = true;
        /** 检测到 API Key 直接拦截（不允许返回带 Key 的内容） */
        private boolean blockApiKey = true;
    }

    @Getter @Setter
    public static class ToolConfig {
        private boolean enabled = true;
        /** 默认角色：user / admin / readonly */
        private String defaultRole = "user";
        /** 每分钟最大工具调用次数（限流） */
        private int rateLimitPerMinute = 60;
        /** 黑名单：永远禁止调用的工具名 */
        private List<String> blockedTools = List.of();
        /** 角色 → 允许工具的映射；空映射表示无限制 */
        private Map<String, List<String>> roleAllowedTools = Map.of();
    }
}