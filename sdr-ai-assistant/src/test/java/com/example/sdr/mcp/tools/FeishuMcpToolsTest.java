package com.example.sdr.mcp.tools;

import com.example.sdr.mcp.mock.FeishuMockClient;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 FeishuMcpTools 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("FeishuMcpTools 单元测试")
class FeishuMcpToolsTest {

    private FeishuMockClient client;
    private FeishuMcpTools tools;

    @BeforeEach
    void setup() {
        client = new FeishuMockClient();
        client.clear();
        tools = new FeishuMcpTools(client);
    }

    @Test
    @DisplayName("【发送】feishuNotify 返回通知 ID 并写入 FeishuMockClient")
    void testNotify() {
        String id = tools.feishuNotify("https://webhook/xxx", "测试标题", "**粗体**正文");
        assertThat(id).startsWith("feishu-");
        FeishuMockClient.Notification r = client.peekLatest();
        assertThat(r.title()).isEqualTo("测试标题");
        assertThat(r.content()).contains("粗体");
        log.info("✅ 飞书通知：id={}", id);
    }
}