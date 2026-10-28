package com.example.sdr.mcp.tools;

import com.example.sdr.tools.SmtpVerifyTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 SmtpMcpTools 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@DisplayName("SmtpMcpTools 单元测试")
class SmtpMcpToolsTest {

    private SmtpMcpTools tools;

    @BeforeEach
    void setup() {
        tools = new SmtpMcpTools(new SmtpVerifyTool());
    }

    @Test
    @DisplayName("【deliverable】白名单域名")
    void testDeliverable() {
        SmtpVerifyTool.SmtpStatus r = tools.smtpVerify("a@example.com");
        assertThat(r.status()).isEqualTo("deliverable");
        log.info("✅ deliverable：{}", r);
    }

    @Test
    @DisplayName("【undeliverable】一次性域名")
    void testUndeliverable() {
        SmtpVerifyTool.SmtpStatus r = tools.smtpVerify("a@mailinator.com");
        assertThat(r.status()).isEqualTo("undeliverable");
        log.info("✅ undeliverable：{}", r);
    }
}