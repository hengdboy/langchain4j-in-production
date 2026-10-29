package com.example.assistant.assistant;

import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * 新增：调用工具的 AiService 接口
 *
 * 注意：这里没有加 @AiService 注解，因为工具注册和 maxSequentialToolsInvocations
 * 需要用 AiServices.builder() 手动配置（见 ToolConfig.toolAssistant()）。
 * 如果加了 @AiService，starter 会自动注册一个不带工具的 Bean，和手动的 Bean 冲突。
 *
 * 框架自动处理：
 * - ChatMemory 隔离（@MemoryId）
 * - Tool Call 多轮循环（最多 10 次，由 ToolConfig 配置）
 * - 把工具结果作为上下文给 LLM
 *
 *最佳实践：返回 Result<String> 而非 String，这样可以拿到：
 * - content()：最终回答
 * - tokenUsage()：Token 用量（用于成本核算）
 * - toolExecutions()：本次调用执行了哪些工具（用于审计和排错）
 * @author hengdboy
 */
public interface ToolAssistant {

    Result<String> chat(@MemoryId Long userId, @UserMessage String question, InvocationParameters parameters);
}