package com.example.assistant.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 新增：StreamingChatAssistant —— 演示 AiService 返回 TokenStream
 * <p>
 * 关键：方法返回 TokenStream，框架自动处理：
 * - ChatMemory 加载与追加（仅在 onCompleteResponse 时追加）
 * - 多轮 Tool Call（如果有 @Tool 标注）
 * - Message 组装
 * <p>
 * 我们只需要在 Controller 里订阅回调。
 * <p>
 * 注意：这里用 @MemoryId 而不是 String，是因为 AiServiceConfig 里的
 * ChatMemoryProvider 是按 Object 类型作为 key 的。生产环境建议用 Long/UUID。
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider")   // ← 多模型 Bean：显式指定对话/流式模型 + 记忆
public interface StreamingChatAssistant {

    /**
     * 流式多轮对话
     */
    TokenStream chatStream(@MemoryId Long userId, @UserMessage String message);

    /**
     * 流式创意写作（无记忆）
     */
    @SystemMessage("你是一个中文创意写作助手，擅长微小说、诗歌、脚本")
    TokenStream creativeStream(@UserMessage("请根据 {{topic}} 主题创作一段文字, 300字左右") String topic);
}