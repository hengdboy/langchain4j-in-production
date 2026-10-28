package com.example.assistant.controller;

import com.example.assistant.assistant.*;
import com.example.assistant.assistant.ReviewAnalysisAssistant.ReviewAnalysis;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.service.Result;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * v0.4 统一助手控制器
 * <p>
 * 提供以下接口：
 * - POST /api/chat                    多轮对话（带记忆+Token用量）
 * - POST /api/translate               翻译（固定 Prompt）
 * - POST /api/review/analyze          评论分析（结构化返回）
 * - POST /api/creative                创意写作（高温度+frequency_penalty）
 * - POST /api/translate-v2            翻译（动态低温度，覆盖全局参数）
 * - POST /api/review/analyze-v3       JSON 输出（temperature=0）
 * - POST /api/code-review             代码评审（Few-shot + Role Prompting）
 * - POST /api/math/solve              数学题（CoT 触发）
 * - POST /api/tools/chat              工具调用对话（TimeTools+Calculator+OrderTools）
 * <p>
 * 重点：/api/tools/chat 演示 ToolAssistant 如何自动调用 3 类工具
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AssistantController {

    private final ChatAssistant chatAssistant;
    private final VisionChatAssistant visionChatAssistant;
    private final TranslationAssistant translationAssistant;
    private final ReviewAnalysisAssistant reviewAssistant;
    private final FlexibleAssistant flexibleAssistant;
    private final ToolAssistant toolAssistant;
    private final DocExtractionAssistant docExtractionAssistant;

    private final ChatMemoryProvider chatMemoryProvider;


    // ============================================================
    // /  接口（保持兼容）
    // ============================================================

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        Result<String> result = chatAssistant.chat(req.sessionId, req.message);
        log.info("AssistantController|chat 返回了结果：{}", Json.toJson(result));
        ChatMemory chatMemory = chatMemoryProvider.get(req.sessionId);
        log.info("AssistantController|chat 获取了记忆：{}", Json.toJson(chatMemory.messages()));
        return Map.of(
                "answer", result.content(),
                "sessionId", req.sessionId(),
                "inputTokens", result.tokenUsage().inputTokenCount(),
                "outputTokens", result.tokenUsage().outputTokenCount(),
                "totalTokens", result.tokenUsage().totalTokenCount()
        );
    }

    @PostMapping("/api/visionChat")
    public Map<String, Object> visionChat(@RequestBody VisionChatRequest req) {

        if (StringUtils.isBlank(req.imageUrl) && StringUtils.isBlank(req.message)) {
            throw new IllegalArgumentException("Either message or imageUrl must be provided");
        }
        UserMessage userMessage = null;
        if (StringUtils.isBlank(req.imageUrl)) {
            userMessage = UserMessage.from(req.message);
        } else {
            String prompt = req.message == null || req.message.isBlank()
                    ? "请详细描述这张图片的内容，包括其中的主体、文字、颜色和布局。"
                    : req.message;
            userMessage = UserMessage.from(
                    ImageContent.from(req.imageUrl),
                    TextContent.from(prompt)
            );
        }
        Result<String> result = visionChatAssistant.chat(req.sessionId, userMessage);
        log.info("visionChat 返回了结果：{}", Json.toJson(result));
        ChatMemory chatMemory = chatMemoryProvider.get(req.sessionId);
        log.info("visionChat 获取了记忆：{}", Json.toJson(chatMemory.messages()));
        return Map.of(
                "answer", result.content(),
                "sessionId", req.sessionId(),
                "inputTokens", result.tokenUsage().inputTokenCount(),
                "outputTokens", result.tokenUsage().outputTokenCount(),
                "totalTokens", result.tokenUsage().totalTokenCount()
        );
    }

    @PostMapping("/api/translate")
    public Map<String, String> translate(@RequestBody TranslateRequest req) {
        String translated = translationAssistant.translate(
                req.sourceLang(), req.targetLang(), req.text()
        );
        return Map.of(
                "sourceLang", req.sourceLang(),
                "targetLang", req.targetLang(),
                "original", req.text(),
                "translated", translated
        );
    }

    @PostMapping("/api/review/analyze")
    public ReviewAnalysis analyze(@RequestBody ReviewRequest req) {
        return reviewAssistant.analyze(req.review());
    }

    // ============================================================
    // 新增接口：演示 ChatRequestParameters 动态参数覆盖
    // ============================================================

    /**
     * 创意写作：高 temperature + frequency_penalty 抗重复
     */
    @PostMapping("/api/creative")
    public Map<String, Object> creative(@RequestBody CreativeRequest req) {
        ChatRequestParameters params = ChatRequestParameters.builder()
                .temperature(1.0)
                .topP(0.95)
                .frequencyPenalty(0.5)
                .maxOutputTokens(1000)
                .build();

        String content = flexibleAssistant.creativeWrite(req.topic(), params);
        return Map.of(
                "topic", req.topic(),
                "content", content,
                "temperature", 1.0,
                "note", "高 temperature + frequency_penalty=0.5 让输出更多样且不啰嗦"
        );
    }

    /**
     * 翻译 v2：低 temperature + seed 保证稳定
     */
    @PostMapping("/api/translate-v2")
    public Map<String, Object> translateV2(@RequestBody TranslateRequest req) {
        OpenAiChatRequestParameters params = OpenAiChatRequestParameters.builder()
                .temperature(0.3)
                .maxOutputTokens(500)
                .seed(42)
                .build();

        String translated = flexibleAssistant.translate(req.text(), params);
        return Map.of(
                "text", req.text(),
                "translated", translated,
                "temperature", 0.3,
                "note", "低 temperature + seed=42 让翻译可复现"
        );
    }

    /**
     * JSON 输出 v3：temperature=0 + responseFormat=JSON
     */
    @PostMapping("/api/review/analyze-v3")
    public Map<String, Object> analyzeV3(@RequestBody ReviewRequest req) {
        // 演示 OpenAiChatRequestParameters 子类，可以加 responseFormat
        OpenAiChatRequestParameters params = OpenAiChatRequestParameters.builder()
                .temperature(0.0)
                .maxOutputTokens(500)
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                .seed(42)
                .build();

        ReviewAnalysis result = flexibleAssistant.analyze(req.review(), params);
        return Map.of(
                "review", req.review(),
                "sentiment", result.sentiment(),
                "score", result.rating(),
                "keyPoints", result.keyPoints(),
                "note", "temperature=0 + JSON_OBJECT 让结构化输出 99% 成功"
        );
    }

    /**
     * 代码评审：Few-shot + Role Prompting
     */
    @PostMapping("/api/code-review")
    public Map<String, Object> reviewCode(@RequestBody CodeReviewRequest req) {
        String review = flexibleAssistant.reviewCode(req.language(), req.code());
        return Map.of(
                "language", req.language(),
                "review", review,
                "note", "Few-shot 3 个示例让模型学会输出格式"
        );
    }

    /**
     * 数学题：CoT 触发
     */
    @PostMapping("/api/math/solve")
    public Map<String, Object> solveMath(@RequestBody MathRequest req) {
        String solution = flexibleAssistant.solveMath(req.question());
        return Map.of(
                "question", req.question(),
                "solution", solution,
                "note", "Prompt 包含'一步步思考'触发 CoT，推理过程更可靠"
        );
    }

    // ============================================================
    // 新增接口：工具调用对话
    // ============================================================

    /**
     * 工具调用对话：自动选择 TimeTools / CalculatorTools / OrderTools
     * <p>
     * 试试这些问题：
     * - "现在几点了？" → 调 TimeTools.getCurrentTime()
     * - "帮我算一下 3.14 * 25.6 等于多少" → 调 CalculatorTools.multiply()
     * - "帮我查一下订单号 1001 的状态" → 调 OrderTools.getOrderStatus()（需审批）
     */
    @PostMapping("/api/tools/chat")
    public Map<String, Object> toolChat(@RequestBody ToolChatRequest req) {
        // 获取当前参数，传入到llm,做用户数据隔离，这个数据不会传到llm,主要做tool调用
        InvocationParameters parameters = InvocationParameters.from(Map.of("tenantId", 1001, "userId", req.userId));
        Result<String> result = toolAssistant.chat(req.userId(), req.question(), parameters);

        // 提取工具执行记录的摘要（工具名 + 入参 + 返回值）
        var toolSummaries = result.toolExecutions() == null ? List.of()
                : result.toolExecutions().stream()
                .map(te -> Map.of(
                        "tool", te.request().name(),
                        "method", te.request().name(),
                        "arguments", te.request().arguments() != null ? te.request().arguments() : "{}",
                        "result", te.result() != null ? te.result().substring(0, Math.min(200, te.result().length())) : ""
                ))
                .toList();

        return Map.of(
                "userId", req.userId(),
                "question", req.question(),
                "answer", result.content(),
                "toolExecutions", toolSummaries,
                "inputTokens", result.tokenUsage().inputTokenCount(),
                "outputTokens", result.tokenUsage().outputTokenCount(),
                "totalTokens", result.tokenUsage().totalTokenCount(),
                "note", "模型会自动判断是否需要调用工具、调用哪个工具"
        );
    }

    // ============================================================
    // 新增：文档关键信息提取（结构化输出）
    // ============================================================

    /**
     * 文档关键信息提取：从合同/发票/简历等文本中提取关键字段
     */
    @PostMapping("/api/doc/extract")
    public Map<String, Object> extractDoc(@RequestBody DocExtractRequest req) {
        try {
            DocExtractionAssistant.DocExtraction result = docExtractionAssistant.extract(req.document());
            return Map.of(
                    "success", true,
                    "data", result,
                    "extractedAt", LocalDateTime.now().toString()
            );
        } catch (Exception e) {
            log.warn("文档提取失败: docLength={}, error={}",
                    req.document() == null ? 0 : req.document().length(), e.getMessage());
            return Map.of(
                    "success", false,
                    "error", "文档解析失败，请检查文档格式后重试",
                    "extractedAt", LocalDateTime.now().toString()
            );
        }
    }


    // ========== DTOs ==========

    public record ChatRequest(String sessionId, String message) {
    }

    public record VisionChatRequest(String sessionId, String message, String imageUrl) {
    }

    public record TranslateRequest(String sourceLang, String targetLang, String text) {
    }

    public record ReviewRequest(String review) {
    }

    public record CreativeRequest(String topic) {
    }

    public record CodeReviewRequest(String language, String code) {
    }

    public record MathRequest(String question) {
    }

    public record ToolChatRequest(Long userId, String question) {
    }

    public record DocExtractRequest(String document) {
    }
}