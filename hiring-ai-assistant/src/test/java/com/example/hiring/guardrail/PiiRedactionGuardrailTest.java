package com.example.hiring.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.29 PII 脱敏 guardrail 单元测试。
 *
 * @author hengdboy
 * @since v0.29
 */
@Slf4j
class PiiRedactionGuardrailTest {

    private final PiiRedactionGuardrail guardrail = new PiiRedactionGuardrail();

    @Test
    @DisplayName("【邮箱】命中 → 替换为 [EMAIL]")
    void testEmailRedaction() {
        AiMessage msg = AiMessage.from("Contact me at alice@example.com for details");
        OutputGuardrailResult result = guardrail.validate(msg);
        assertThat(result.result().name()).startsWith("SUCCESS");
        assertThat(result.successfulText()).contains("[EMAIL]").doesNotContain("alice@example.com");
        log.info("邮箱脱敏通过：{}", result.successfulText());
    }

    @Test
    @DisplayName("【手机号 + 银行卡】多重命中 → 全部替换")
    void testMultiplePiiTypes() {
        AiMessage msg = AiMessage.from("Phone 13800138000, card 4111111111111111");
        OutputGuardrailResult result = guardrail.validate(msg);
        assertThat(result.successfulText()).contains("[PHONE]").contains("[CC]")
                .doesNotContain("13800138000").doesNotContain("4111111111111111");
        log.info("手机+卡脱敏通过");
    }

    @Test
    @DisplayName("【无 PII】原样返回")
    void testNoPii() {
        AiMessage msg = AiMessage.from("Hello, this is a normal business inquiry about smart home devices.");
        OutputGuardrailResult result = guardrail.validate(msg);
        assertThat(result.result().name()).startsWith("SUCCESS");
        log.info("无 PII → 原样通过");
    }
}