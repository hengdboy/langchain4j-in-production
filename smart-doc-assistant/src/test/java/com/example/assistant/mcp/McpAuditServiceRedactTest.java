package com.example.assistant.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch19：MCP 审计参数脱敏。
 *
 * <p>回归点：旧实现用正则匹配精确的小写字段名，{@code apiKey} / {@code accessToken} /
 * {@code passwordHash} 全部漏掉——文档却声称"容忍后缀"。现在按 JSON 结构递归，
 * 字段名忽略大小写、按片段匹配。
 *
 * @author hengdboy
 */
@DisplayName("McpAuditService.redact：按结构脱敏敏感字段")
class McpAuditServiceRedactTest {

    @Test
    @DisplayName("null / 空串原样返回")
    void nullAndEmptyPassThrough() {
        assertThat(McpAuditService.redact(null)).isNull();
        assertThat(McpAuditService.redact("")).isEmpty();
    }

    @Test
    @DisplayName("驼峰与带后缀的敏感字段也被脱敏")
    void camelCaseAndSuffixedKeysAreRedacted() {
        String redacted = McpAuditService.redact(
                "{\"apiKey\":\"k-123\",\"accessToken\":\"t-456\",\"passwordHash\":\"h-789\"}");

        assertThat(redacted)
                .doesNotContain("k-123", "t-456", "h-789")
                .contains("\"apiKey\":\"***\"")
                .contains("\"accessToken\":\"***\"")
                .contains("\"passwordHash\":\"***\"");
    }

    @Test
    @DisplayName("嵌套对象与数组里的敏感字段同样被脱敏")
    void nestedStructuresAreRedacted() {
        String redacted = McpAuditService.redact(
                "{\"user\":{\"name\":\"alice\",\"password\":\"p@ss\"},"
                        + "\"entries\":[{\"secret\":\"s-1\"},{\"label\":\"primary\"}]}");

        assertThat(redacted)
                .doesNotContain("p@ss", "s-1")
                .contains("\"name\":\"alice\"")
                .contains("\"label\":\"primary\"");
    }

    @Test
    @DisplayName("非敏感字段保持原值，数字/布尔类型不变")
    void nonSensitiveValuesUntouched() {
        String redacted = McpAuditService.redact(
                "{\"question\":\"退货政策\",\"topK\":3,\"strict\":true}");

        assertThat(redacted).isEqualTo("{\"question\":\"退货政策\",\"topK\":3,\"strict\":true}");
    }

    @Test
    @DisplayName("新增 authorization 片段：author 不会被误伤")
    void authorIsNotRedacted() {
        String redacted = McpAuditService.redact(
                "{\"author\":\"hengdboy\",\"authorization\":\"Bearer abc\"}");

        assertThat(redacted)
                .contains("\"author\":\"hengdboy\"")
                .contains("\"authorization\":\"***\"");
    }

    @Test
    @DisplayName("无法解析的入参不原样透传")
    void unparseableArgsAreNotPassedThrough() {
        assertThat(McpAuditService.redact("{not json")).isEqualTo("[unparseable]");
    }
}
