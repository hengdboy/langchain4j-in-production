package com.example.assistant.assistant;

import dev.langchain4j.service.*;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 通用聊天助手（v0.2）
 *
 * 升级点：
 * - 增加 @MemoryId 支持多会话记忆隔离
 * - 返回 Result<String> 拿Token用量等元数据（生产必用）
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",              // ← 多 ChatModel Bean 时显式指定默认对话模型
        chatMemoryProvider = "chatMemoryProvider")  // ← EXPLICIT 模式需显式声明记忆
/**
 * @author hengdboy
 */
public interface ChatAssistant {

    /**
     * 多轮对话
     *
     * @param sessionId 会话/用户ID，用于记忆隔离
     * @param message   用户消息
     * @return 包装了回复文本、Token用量、工具执行记录的Result
     */
    @SystemMessage("你是一个友好的AI助手，请用简洁的中文回答用户的问题。")
    @UserMessage("{{it}}")
    Result<String> chat(@MemoryId String sessionId, @V("it") String message);
}
