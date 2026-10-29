package com.example.assistant.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Guardrails：<strong>Application 层</strong>的输入预处理。
 *
 * <p>与官方 {@code InputGuardrail}（仅在 {@code AiServices} 层工作）的区别：
 * <ul>
 * <li>本类在 Controller 接收用户输入后、调用 AiService 之前生效</li>
 * <li>职责：清理"原始用户输入"——去 HTML / 控制字符 / 截断过长输入</li>
 * <li>提示注入检测由官方 {@code PatternBasedPromptInjectionGuardrail} 接管（在 AiServices 层）</li>
 * </ul>
 *
 * <h2>两层 Guardrail 架构</h2>
 * <pre>
 * Controller 接收用户输入（HTTP/JSON）
 * ↓
 * [Application 层] InputGuard.sanitize()          ← 本类（去 HTML / 截断 / 控字符）
 * ↓  cleaned input
 * [AiServices 层] PatternBasedPromptInjectionGuardrail   ← 官方（注入检测）
 * ↓  validated input
 * ChatModel.chat()
 * ↓  AiMessage
 * [AiServices 层] JsonExtractorOutputGuardrail / 自定义 PII redactor
 * ↓  validated output
 * Controller 返回响应
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.18
 */
@Slf4j
@Component
public class InputGuard {

    /** 连续 4 个以上空白压缩为单个空格（保留换行） */
    private static final Pattern MULTI_BLANK = Pattern.compile("[ \\t]{4,}");

    /** HTML 标签：去除尖括号包裹的标签（含自闭合） */
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    /** 控制字符（保留换行 \n、回车 \r、制表 \t） */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    private final GuardrailProperties properties;

    public InputGuard(GuardrailProperties properties) {
        this.properties = properties;
    }

    /**
     * Application 层输入清洗：去除危险字符 + 截断过长输入。
     *
     * @param input 原始用户输入（可能为 null）
     * @return 清洗后的字符串（保证非 null）；过长时末尾追加 {@code ...[truncated]}
     */
    public String sanitize(String input) {
        if (input == null) {
            return "";
        }
        if (!properties.getInput().isEnabled()) {
            return input;
        }
        String s = input;
        if (properties.getInput().isStripHtml()) {
            s = HTML_TAGS.matcher(s).replaceAll(" ");
        }
        if (properties.getInput().isStripControlChars()) {
            s = CONTROL_CHARS.matcher(s).replaceAll(" ");
        }
        if (properties.getInput().isCompressBlank()) {
            s = MULTI_BLANK.matcher(s).replaceAll(" ");
        }
        int maxLen = properties.getInput().getMaxLength();
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen) + "...[truncated]";
            log.warn("[Guardrail] input truncated to {} chars", maxLen);
        }
        return s.strip();
    }
}