package com.example.sdr.mcp.tools;

import com.example.sdr.tools.MailGateway;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 EmailMcpTools 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("EmailMcpTools 单元测试")
class EmailMcpToolsTest {

    private MailGateway mailGateway;
    private EmailMcpTools tools;

    @BeforeEach
    void setup() {
        mailGateway = new MailGateway();
        mailGateway.clear();
        tools = new EmailMcpTools(mailGateway);
    }

    @Test
    @DisplayName("【发送】emailSend 写入 MailGateway 并返回 messageId")
    void testSend() {
        String mid = tools.emailSend("buyer@example.com", "Subject", "Body", "en");
        assertThat(mid).startsWith("mock-");
        assertThat(mailGateway.peekLatest().to()).isEqualTo("buyer@example.com");
        log.info("✅ 发送：messageId={}", mid);
    }

    @Test
    @DisplayName("【列表】emailListSent 取最近 N 条")
    void testList() {
        tools.emailSend("a@example.com", "S1", "B1", "en");
        tools.emailSend("b@example.com", "S2", "B2", "de");
        List<MailGateway.SentRecord> r = tools.emailListSent(5);
        assertThat(r).hasSize(2);
        log.info("✅ 列表：{} 条", r.size());
    }
}