package com.example.assistant.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guardrails：输出层防护。
 *
 * <p>三条防线（每条可独立开关）：
 * <ol>
 * <li>{@link PiiRedactor}：PII 信息脱敏（手机号 / 邮箱 / 身份证 / 银行卡）</li>
 * <li>{@link ApiKeyLeakDetector}：API Key / Token 泄露拦截（与  audit 的 redact 互为补）</li>
 * <li>{@link #guard(String)}：整合入口，逐条应用，命中即抛异常（默认行为）</li>
 * </ol>
 *
 * <h2>为什么必须脱敏而非"只过滤"</h2>
 * <p>"过滤"=拒绝输出（用户体验差：用户看不到任何东西）；"脱敏"=保留上下文（用户能看到自己的信息被部分隐藏）。
 * 例如输出"你的手机号 138****1234 已绑定"——前 3+后 4 可见，中间打码。
 *
 * <h2>API Key 泄露为什么必须拦截</h2>
 * <p>LLM 训练数据可能含别人泄露的 Key；如果用户在 prompt 里贴了带 Key 的上下文，
 * 模型可能在输出里"复述"——这就是把用户的 Key 暴露给下一个访问者。
 * 必须用 {@link ApiKeyLeakDetector} 在输出后再次 redact 并计数。
 *
 * @author langchain4j-tutorial
 * @since v0.18
 */
@Slf4j
@Component
public class OutputGuard {

    /**
     * 中国大陆手机号：1[3-9]xxxxxxxxx → 138****1234
     */
    static final Pattern CN_MOBILE = Pattern.compile("\\b1[3-9]\\d{9}\\b");

    /**
     * 邮箱：xxx@yyy.zzz → x***@yyy.zzz
     */
    static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    /**
     * 中国大陆身份证：18 位数字或 17 位数字 + X → 前 6 + **** + 后 4
     */
    static final Pattern CN_ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");

    /**
     * 银行卡：13~19 位连续数字（粗略启发式）
     */
    static final Pattern BANK_CARD = Pattern.compile("\\b\\d{13,19}\\b");

    /**
     * API Key 常见前缀：sk- / ghp_ / AKIA / Bearer / sk-or- / Bearer
     */
    static final Pattern API_KEY = Pattern.compile(
            "(?i)\\b((?:sk-|sk-or-|ghp_|gho_|github_pat_|AKIA[A-Z0-9]{16}|Bearer\\s+)[A-Za-z0-9._\\-]{16,})");

    private final GuardrailProperties properties;
    private final GuardrailMetrics metrics;

    public OutputGuard(GuardrailProperties properties, GuardrailMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 输出层总入口：依次脱敏 / 拦截。
     *
     * <p>行为：
     * <ul>
     * <li>PII（手机/邮箱/身份证/银行卡）：<strong>脱敏</strong>——替换为打码</li>
     * <li>API Key：<strong>拦截</strong>——抛 {@link GuardrailViolation}，由 Controller 返回 500/阻止</li>
     * </ul>
     */
    public String guard(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }
        if (!properties.getOutput().isEnabled()) {
            return output;
        }
        String result = output;
        result = redactPii(result);
        result = detectApiKeyLeak(result);   // 命中即抛
        return result;
    }

    /**
     * PII 脱敏（默认行为：保留部分 + 打码）。命中计数用于告警"业务侧有未脱敏数据流入"。
     */
    String redactPii(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;
        // 手机号：前 3 + **** + 后 4
        if (properties.getOutput().isRedactPhone()) {
            result = replace(result, CN_MOBILE, m -> m.group().substring(0, 3) + "****" + m.group().substring(7),
                    "phone");
        }
        // 邮箱：首字母 + ***@domain
        if (properties.getOutput().isRedactEmail()) {
            result = replace(result, EMAIL, m -> m.group(1) + "***" + m.group(2), "email");
        }
        // 身份证：前 6 + **** + 后 4
        if (properties.getOutput().isRedactIdCard()) {
            result = replace(result, CN_ID_CARD, m -> {
                String s = m.group();
                return s.substring(0, 6) + "****" + s.substring(14);
            }, "idcard");
        }
        // 银行卡：保留前 4 + 后 4
        if (properties.getOutput().isRedactBankCard()) {
            result = replace(result, BANK_CARD, m -> {
                String s = m.group();
                if (s.length() < 16) return s;  // 太短不处理（可能是误判）
                return s.substring(0, 4) + "********" + s.substring(s.length() - 4);
            }, "bankcard");
        }
        return result;
    }

    /**
     * 检测 API Key 泄露——命中即抛异常（不允许返回带 Key 的内容）。
     */
    String detectApiKeyLeak(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!properties.getOutput().isBlockApiKey()) return text;
        Matcher m = API_KEY.matcher(text);
        if (m.find()) {
            log.error("[Guardrail] API Key leak detected in output: {}", redactForLog(m.group()));
            metrics.incrementApiKeyBlock();
            throw new GuardrailViolation("output.api_key_leaked",
                    "输出检测到 API Key 泄露，已被拦截（保护用户凭证）");
        }
        return text;
    }

    /** 通用正则替换 + 命中计数 */
    private String replace(String text, Pattern p, java.util.function.Function<Matcher, String> replacer, String metricKey) {
        StringBuilder sb = new StringBuilder();
        Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) {
            metrics.incrementPiiRedacted(metricKey, count);
            log.info("[Guardrail] {} redacted: {} occurrences", metricKey, count);
        }
        return sb.toString();
    }

    /** 把 Key 部分打码后再写日志（避免日志成为泄露源） */
    private static String redactForLog(String s) {
        if (s == null || s.length() < 8) return "***";
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }
}