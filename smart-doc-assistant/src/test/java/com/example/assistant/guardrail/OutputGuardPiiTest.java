package com.example.assistant.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch21：OutputGuard PII 脱敏 + 误杀率实测（2026-10-28 数据的固化版，数字随实现走）。
 *
 * @author hengdboy
 * @since v0.19
 */
@DisplayName("OutputGuard：真 PII 漏杀为 0，长数字误杀有边界")
class OutputGuardPiiTest {

    private OutputGuard newGuard() {
        return new OutputGuard(new GuardrailProperties(), new GuardrailMetrics());
    }

    @Test
    @DisplayName("五类真 PII 全部脱敏，0 漏杀")
    void truePiiAllMasked() {
        OutputGuard g = newGuard();
        assertThat(g.guard("您的手机号 13800138000 已绑定")).contains("138****8000");
        assertThat(g.guard("邮箱 zhangsan@example.com 接收验证码")).contains("z***@example.com");
        assertThat(g.guard("身份证 110101199003071234 姓名张某")).contains("110101****1234");
        assertThat(g.guard("银行卡 6222021234567890123 尾号 0123")).contains("6222********0123");
    }

    @Test
    @DisplayName("16-19 位数字被银行卡规则咬住（误杀区间，实测边界）")
    void bankcardRuleBites16to19Digits() {
        OutputGuard g = newGuard();
        assertThat(g.redactPii("编号 1234567812345678 结束")).isNotEqualTo("编号 1234567812345678 结束");
        assertThat(g.redactPii("编号 1234567812345678123 结束")).isNotEqualTo("编号 1234567812345678123 结束");
    }

    @Test
    @DisplayName("20 位以上长数字因词边界整体绕过（实测最反直觉的一条）")
    void longIdsEscapeBankcardRule() {
        OutputGuard g = newGuard();
        String text = "编号 12345678123456781234 结束";
        assertThat(g.redactPii(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("13-15 位数字被 <16 放行闸放过（漏杀代价，Ch21 21.6.1）")
    void shortDigitsPassThrough() {
        OutputGuard g = newGuard();
        String text = "编号 1234567812345 结束";
        assertThat(g.redactPii(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("API Key 拦截：真形态拦，短串不误拦")
    void apiKeyInterception() {
        OutputGuard g = newGuard();
        org.junit.jupiter.api.Assertions.assertThrows(GuardrailViolation.class,
                () -> g.guard("配置项是 sk-or-v1-1234567890abcdefghij"));
        assertThat(g.guard("订单 sk-1234567890 已发货")).isEqualTo("订单 sk-1234567890 已发货");
    }
}
