package com.example.sdr.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * v0.29 Ch21 Guardrails：Prompt 注入检测输入 guardrail。
 *
 * <p>检测常见注入攻击短语（教学版正则 + 关键词匹配，不调分类器）：
 * <ul>
 *   <li>"ignore previous/above instructions"</li>
 *   <li>"you are now / act as"（角色覆写）</li>
 *   <li>"system:" / "&lt;|im_start|&gt;"（system 角色伪前缀）</li>
 *   <li>"disregard ..." / "forget ..."</li>
 * </ul>
 *
 * <p><b>策略</b>：命中则 {@code failure("potential injection")} — 由上层 AiServices
 * 抛 {@code InputGuardrailException}，业务层捕获后把 lead 转 POOL（冷处理）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@Component
public class PromptInjectionGuardrail implements InputGuardrail {

    private static final List<String> RISKY_PHRASES = List.of(
            "ignore previous instructions",
            "ignore above instructions",
            "ignore the above",
            "disregard previous",
            "forget everything",
            "you are now",
            "act as a",
            "system:",
            "<|im_start|>",
            "jailbreak",
            "developer mode"
    );

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String text = userMessage.singleText();
        if (text == null || text.isEmpty()) {
            return success();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : RISKY_PHRASES) {
            if (lower.contains(phrase)) {
                log.warn("🚨 [Injection Guardrail] blocked risky phrase: '{}'", phrase);
                return fatal("potential prompt injection: '" + phrase + "'");
            }
        }
        return success();
    }
}