package com.example.lab;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lab3 注入检测器（Application 层，进 Agent 之前跑）：命中确定性模式即拦截。
 *
 * <p>与主教程 Ch21 的口径一致：第一道用正则而不是 LLM——便宜、快、自己不会被劫持。
 * 规则来自真实注入攻击的高频句式；教学版宁误报不漏报。
 * @author hengdboy
 */
public final class InjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)忽略(之前|以上|前面|上述)(的)?(所有)?(指令|规则|设定|要求)"),
            Pattern.compile("(?i)无视(之前的|以上|所有)?(系统)?(指令|规则|规定|要求|提示词)"),
            Pattern.compile("(?i)(系统提示|system prompt|管理员模式|开发者模式|调试模式)"),
            Pattern.compile("(?i)你(现在|从现在起)(是|扮演)\\s*(运维|开发|管理员|root)")
    );

    private InjectionDetector() {}

    /** 命中返回第一条匹配的模式说明（给日志/审计用），未命中返回 null。 */
    public static String detect(String userInput) {
        if (userInput == null) return null;
        for (Pattern p : PATTERNS) {
            if (p.matcher(userInput).find()) {
                return p.pattern();
            }
        }
        return null;
    }

    /** 便捷断言式入口：命中即抛，Controller 层在调 Agent 之前用它兜底。 */
    public static void requireClean(String userInput) {
        String hit = detect(userInput);
        if (hit != null) {
            throw new RefundGuard.GuardViolation("input.injection_detected",
                    "输入包含疑似提示注入内容，已拦截（命中模式：" + hit + "）");
        }
    }
}
