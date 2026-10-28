package com.example.assistant.assistant;


import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * @author DuHeng
 * @desc 多模态聊天助手
 * @since 2026/8/21
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "visionChatModel", chatMemoryProvider = "chatMemoryProvider")
public interface VisionChatAssistant {

    /**
     * 多模态聊天
     *
     * @param sessionId 会话ID
     * @param message   用户消息
     * @return 聊天响应结果
     **/
    Result<String> chat(@MemoryId String sessionId, UserMessage message);
}
