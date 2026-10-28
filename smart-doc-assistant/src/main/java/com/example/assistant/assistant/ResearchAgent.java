package com.example.assistant.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * 新增：研究助手 Agent 接口
 *
 * <p>注意：这里<b>没有</b>用 {@code @AiService} 注解，而是用 {@code AiServices.builder()}
 * 手动装配（见 AgentConfig）——因为要注册多个工具 + 设置 maxToolCallingRoundTrips（防循环）。
 * 框架在底层自动跑"模型调用 → 工具执行 → 再把结果喂回模型"的循环，直到模型不再请求工具。
 *
 * <p>返回值用 {@code Result<String>} 而非 {@code String}，可额外拿到 tokenUsage / toolExecutions 用于审计。
 */
public interface ResearchAgent {

    Result<String> chat(@MemoryId Long sessionId, @UserMessage String question);
}
