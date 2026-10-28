package com.example.assistant.assistant;

import com.example.assistant.assistant.ReviewAnalysisAssistant.ReviewAnalysis;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 新增：FlexibleAssistant —— 演示 ChatRequestParameters 单次覆盖参数
 *
 * 对比 ChatAssistant（基础）和 FlexibleAssistant（参数动态）：
 * - ChatAssistant：固定全局参数，适合简单对话
 * - FlexibleAssistant：每个方法按场景传入不同参数
 *
 * 关键：参数类型必须是 ChatRequestParameters，命名随意，框架会自动识别并合并
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "openAiChatModel")   // ← 多模型 Bean 显式指定默认对话模型
public interface FlexibleAssistant {

    /**
     * 创意写作：高 temperature + frequency_penalty 抗重复
     * 典型调用：
     * params = ChatRequestParameters.builder()
     * .temperature(1.0)
     * .topP(0.95)
     * .frequencyPenalty(0.5)
     * .maxTokens(2000)
     * .build();
     */
    @SystemMessage("你是一位资深品牌文案，擅长写有温度、有故事感的内容。" +
                   "文风：优雅但不矫情，简洁但有信息密度。")
    String creativeWrite(@UserMessage String topic, ChatRequestParameters params);

    /**
     * 翻译：低 temperature 保证准确
     */
    @SystemMessage("你是一名专业翻译，只输出译文，不要任何解释或客套话。")
    String translate(@UserMessage String text, ChatRequestParameters params);

    /**
     * JSON 结构化输出：temperature=0 + responseFormat=JSON
     */
    ReviewAnalysis analyze(@UserMessage String review, ChatRequestParameters params);

    /**
     * 代码评审：Few-shot + Role Prompting 组合（外部模板）
     */
    @SystemMessage(fromResource = "prompts/code-review-system.txt")
    String reviewCode(@V("language") String language, @V("code") String code);

    /**
     * 数学题：CoT 触发
     */
    @SystemMessage("你是一名数学老师，先一步步推理再给出最终答案。" +
                   "用中文回答，推理步骤清晰明了。")
    @UserMessage("题目：{{question}}\n\n请一步步思考后给出答案。")
    String solveMath(@V("question") String question);
}