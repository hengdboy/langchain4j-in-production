package com.example.assistant.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ch19：MCP Server 端鉴权。
 *
 * <p>回归点：缺省的 {@code dev-mcp-key-replace-in-production} 长度 34，能通过"至少 16 字符"
 * 校验，只在日志里 WARN 一下就上线——端点等于没有鉴权。现在改为启动期直接抛异常。
 *
 * @author hengdboy
 */
@DisplayName("McpSecurityService：启动期拒绝弱 Key，运行期常量时间比对")
class McpSecurityServiceTest {

    private static final String STRONG_KEY = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("空 Key 启动失败")
    void blankKeyFailsFast() {
        assertThatThrownBy(() -> security("").validateConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.mcp.api-key");
        assertThatThrownBy(() -> security(null).validateConfig())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("过短 Key 启动失败")
    void shortKeyFailsFast() {
        assertThatThrownBy(() -> security("short-key").validateConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 16");
    }

    @Test
    @DisplayName("历史演示 Key 启动失败（不再降级为 WARN）")
    void legacyDemoKeyFailsFast() {
        assertThatThrownBy(() -> security("dev-mcp-key-replace-in-production").validateConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy demo key");
    }

    @Test
    @DisplayName("强 Key 通过校验")
    void strongKeyPasses() {
        assertThatCode(() -> security(STRONG_KEY).validateConfig()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Bearer scheme 大小写不敏感，错误 Key 一律拒绝")
    void bearerSchemeIsCaseInsensitive() {
        McpSecurityService security = security(STRONG_KEY);

        assertThat(security.isAuthorized("Bearer " + STRONG_KEY, null)).isTrue();
        assertThat(security.isAuthorized("bearer " + STRONG_KEY, null)).isTrue();
        assertThat(security.isAuthorized("BEARER " + STRONG_KEY, null)).isTrue();
        assertThat(security.isAuthorized("  Bearer   " + STRONG_KEY + "  ", null)).isTrue();

        assertThat(security.isAuthorized("Bearer wrong-key", null)).isFalse();
        assertThat(security.isAuthorized("Basic " + STRONG_KEY, null)).isFalse();
        assertThat(security.isAuthorized(null, null)).isFalse();
    }

    @Test
    @DisplayName("X-MCP-API-Key 备选通道可用")
    void customHeaderWorks() {
        McpSecurityService security = security(STRONG_KEY);

        assertThat(security.isAuthorized(null, STRONG_KEY)).isTrue();
        assertThat(security.isAuthorized(null, "wrong-key")).isFalse();
    }

    @Test
    @DisplayName("工具白名单与 KnowledgeBaseMcpTools 单一真相源一致")
    void whitelistMatchesToolNames() {
        McpSecurityService security = security(STRONG_KEY);

        assertThat(security.allowedToolList()).isEqualTo(KnowledgeBaseMcpTools.TOOL_NAMES);
        assertThat(security.isToolAllowed("searchKnowledgeBase")).isTrue();
        assertThat(security.isToolAllowed("askWithRag")).isTrue();
        assertThat(security.isToolAllowed("getKnowledgeBaseStats")).isTrue();
        assertThat(security.isToolAllowed("executeShell")).isFalse();
        assertThat(security.isToolAllowed(null)).isFalse();
    }

    private static McpSecurityService security(String key) {
        return new McpSecurityService(key);
    }
}
