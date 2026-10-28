package com.example.lab;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lab5 PII 脱敏器：简历进 LLM 之前先打码——模型只需要"这个人行不行"，
 * 不需要"这个人是谁"。三条正则与主教程  OutputGuard 同构（手机/邮箱/身份证）。
 */
public final class PiiRedactor {

    static final Pattern CN_MOBILE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    static final Pattern CN_ID = Pattern.compile("\\b\\d{17}[\\dXx]\\b");

    private PiiRedactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        s = replace(CN_MOBILE, s, m -> m.group().substring(0, 3) + "****" + m.group().substring(7));
        s = replace(EMAIL, s, m -> m.group(1) + "***" + m.group(2));
        s = replace(CN_ID, s, m -> m.group().substring(0, 6) + "********" + m.group().substring(14));
        return s;
    }

    /** 脱敏是否彻底：输出里不能再有完整手机号/邮箱/身份证（验收硬指标）。 */
    public static boolean isClean(String output) {
        if (output == null) return true;
        return !CN_MOBILE.matcher(output).find()
                && !CN_ID.matcher(output).find()
                && !EMAIL.matcher(output).find();
    }

    private static String replace(Pattern p, String text, java.util.function.Function<Matcher, String> fn) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
