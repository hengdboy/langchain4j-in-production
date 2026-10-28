package com.example.sdr.mcp;

import com.example.sdr.mcp.mock.FeishuMockClient;
import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.MailGateway;
import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.community.mcp.server.McpServer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 McpServerFactory 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("McpServerFactory 单元测试")
class McpServerFactoryTest {

    @Test
    @DisplayName("工厂构造 McpServer：5 个 Tools Bean 注册成功")
    void testCreate() {
        McpServer server = McpServerFactory.create(
                new CustomsLookupTool(),
                new SmtpVerifyTool(),
                new KpFinderTool(),
                new MailGateway(),
                new FeishuMockClient());
        assertThat(server).isNotNull();
        assertThat(McpServerFactory.TOOL_NAMES).hasSize(6);
        assertThat(McpServerFactory.SERVER_NAME).isEqualTo("sdr-ai-assistant");
        assertThat(McpServerFactory.SERVER_VERSION).isEqualTo("0.27.0");
        log.info("✅ McpServerFactory：{} 暴露 {} 个工具",
                McpServerFactory.SERVER_NAME, McpServerFactory.TOOL_NAMES);
    }
}