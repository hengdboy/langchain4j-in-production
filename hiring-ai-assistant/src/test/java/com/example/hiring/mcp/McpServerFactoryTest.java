package com.example.hiring.mcp;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import com.example.hiring.tools.MailGateway;
import dev.langchain4j.community.mcp.server.McpServer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Ch24 v0.30 MCP Server 工厂单元测试（工具白名单装配）。
 *
 * @author langchain4j-tutorial
 * @since v0.27 → v0.30
 */
@Slf4j
class McpServerFactoryTest {

    @Test
    @DisplayName("【装配】6 个工具注册成功，构造不抛")
    void testCreate() {
        assertThatCode(() -> {
            McpServer server = McpServerFactory.create(
                    new HiringDirectoryMock(), new MailGateway());
            assertThat(server).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("【白名单】TOOL_NAMES 覆盖 4 类查询 + 邮件两件")
    void testToolNames() {
        assertThat(McpServerFactory.TOOL_NAMES).containsExactlyInAnyOrder(
                "getCandidateStatus", "listCandidatesByStatus",
                "listOpenJobs", "listInterviews",
                "emailListSent", "emailSend");
        assertThat(McpServerFactory.SERVER_NAME).isEqualTo("hiring-ai-assistant");
    }
}
