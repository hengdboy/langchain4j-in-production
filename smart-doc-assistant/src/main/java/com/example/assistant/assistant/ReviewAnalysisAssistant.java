package com.example.assistant.assistant;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

import java.util.List;

/**
 * 客户评论分析助手
 *
 * 演示：
 * - 结构化输出（直接返回 POJO record）
 * - 嵌套 enum、List<String>
 * - @UserMessage 从外部资源文件加载
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "jsonModel")   // ← ：结构化输出用 jsonModel（低温度+JSON Mode），多模型 Bean 需 EXPLICIT
public interface ReviewAnalysisAssistant {

    /**
     * 分析客户评论，返回结构化结果
     */
    @UserMessage(fromResource = "prompts/review-analysis-user.txt")
    ReviewAnalysis analyze(String review);

    // ========== 结构化输出类型 ==========

    enum Sentiment {
        /** 正面 */
        POSITIVE,
        /** 负面 */
        NEGATIVE,
        /** 中性 */
        NEUTRAL
    }

    /**
     * 评论分析结果
     *
     * 注意：这是 record（Java 16+），不可变，自动生成构造函数/getter/equals/toString。
     * 也可以用普通 class + 无参构造 + getter/setter。
     */
    record ReviewAnalysis(
            /** 情感倾向 */
            Sentiment sentiment,

            /** 1-5星评分 */
            Integer rating,

            /** 评论提到的关键要点 */
            List<String> keyPoints,

            /** 针对负面评论的改进建议，正面/中性为空数组 */
            List<String> suggestions
    ) {}
}
