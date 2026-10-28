package com.example.lab;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Lab5 简历初筛服务：脱敏 → LLM 打分 → 等级归一化。
 *
 * <p>三步都在一个入口里：简历原文先过 {@link PiiRedactor}（模型只看打码版），
 * 结构化输出拿回打分对象，最后把模型的等级表述（"A"/"A级"/"a"）归一成枚举口径。
 */
public final class ResumeScreeningService {

    /** 结构化输出契约：字段就是给 HR 看的决策依据。 */
    public static class ScreeningResult {
        private int matchScore;            // 0-100
        private String grade;              // 模型原始输出（可能是 "A级"/"a" 等写法）
        private Grade normalizedGrade;     // 归一化后的枚举口径
        private String strengthsSummary;   // 一句话亮点
        private String risksSummary;       // 一句话风险
        private boolean recommendInterview;

        public int getMatchScore() { return matchScore; }
        public void setMatchScore(int matchScore) { this.matchScore = matchScore; }
        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
        public Grade getNormalizedGrade() { return normalizedGrade; }
        public void setNormalizedGrade(Grade normalizedGrade) { this.normalizedGrade = normalizedGrade; }
        public String getStrengthsSummary() { return strengthsSummary; }
        public void setStrengthsSummary(String strengthsSummary) { this.strengthsSummary = strengthsSummary; }
        public String getRisksSummary() { return risksSummary; }
        public void setRisksSummary(String risksSummary) { this.risksSummary = risksSummary; }
        public boolean isRecommendInterview() { return recommendInterview; }
        public void setRecommendInterview(boolean recommendInterview) { this.recommendInterview = recommendInterview; }
    }

    public enum Grade { A, B, C, UNKNOWN }

    interface Assistant {
        @UserMessage("""
                你是 HR 初筛助手。对照【岗位要求】评估【简历】，输出 JSON：
                matchScore（0-100 整数）、grade（A=高度匹配可直推面试 / B=基本匹配待复核 / C=差距明显）、
                strengthsSummary（一句话亮点）、risksSummary（一句话风险）、recommendInterview（布尔）。
                只依据简历内容评估，简历里没有的信息不要脑补。

                【岗位要求】
                {{jd}}

                【简历】
                {{resume}}
                """)
        ScreeningResult screen(@V("jd") String jd, @V("resume") String resume);
    }

    private final Assistant assistant;

    public ResumeScreeningService(ChatModel model) {
        this.assistant = AiServices.create(Assistant.class, model);
    }

    public ScreeningResult screen(String jd, String resumeText) {
        String masked = PiiRedactor.redact(resumeText);   // ① 模型只见打码版
        ScreeningResult raw = assistant.screen(jd, masked);  // ② 结构化打分
        raw.normalizedGrade = normalizeGrade(raw.grade);  // ③ 口径归一（原始字符串保留在 grade 字段）
        if (raw.matchScore < 0) raw.matchScore = 0;
        if (raw.matchScore > 100) raw.matchScore = 100;
        if (!PiiRedactor.isClean(raw.strengthsSummary + raw.risksSummary)) {
            // 输出兜底：万一模型复述了 PII，整条结果作废——宁可错杀不外泄
            throw new IllegalStateException("筛选结果里检测到未脱敏 PII，已拦截");
        }
        return raw;
    }

    /** "A级"/"a"/"A（高度匹配）" → A；认不出的进 UNKNOWN，不猜。 */
    public static Grade normalizeGrade(String raw) {
        if (raw == null || raw.isBlank()) return Grade.UNKNOWN;
        String s = raw.trim().toUpperCase();
        for (Grade g : Grade.values()) {
            if (g != Grade.UNKNOWN && s.startsWith(g.name())) return g;
        }
        return Grade.UNKNOWN;
    }
}
