package com.example.hiring.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * v0.29 Ch21 Guardrails：PII 脱敏输出 guardrail。
 *
 * <p>检测并替换 LLM 输出中的邮箱 / 手机号 / 银行卡号。覆盖：
 * <ul>
 * <li>邮箱：{@code [\w.+-]+@[\w-]+\.[\w.-]+}</li>
 * <li>手机号（中国 11 位）：{@code 1[3-9]\d{9}}</li>
 * <li>银行卡（13-19 位连续数字）：{@code \d{13,19}}</li>
 * </ul>
 *
 * <p><b>策略</b>：命中则 redact（{@code successWith(redacted)}），不是 fail —
 * 因为 PII 不该阻断业务流程（教学版），但必须脱敏后再落 DPO / 日志。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@Component
public class PiiRedactionGuardrail implements OutputGuardrail {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_CN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d{13,19}\\b");

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        if (text == null || text.isEmpty()) {
            return success();
        }
        String redacted = text;
        int hits = 0;
        if (EMAIL.matcher(redacted).find()) { redacted = EMAIL.matcher(redacted).replaceAll("[EMAIL]"); hits++; }
        if (PHONE_CN.matcher(redacted).find()) { redacted = PHONE_CN.matcher(redacted).replaceAll("[PHONE]"); hits++; }
        if (CREDIT_CARD.matcher(redacted).find()) { redacted = CREDIT_CARD.matcher(redacted).replaceAll("[CC]"); hits++; }
        if (hits > 0) {
            log.warn("[PII Guardrail] redacted {} PII type(s) from LLM output", hits);
            return successWith(redacted);
        }
        return success();
    }
}
