package com.example.assistant.engineering;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * 新增：工程化 Agent 接口
 *
 * <p>与 ResearchAgent 同构，但装配方式不同——本接口由
 * {@code AiServices.builder()} 加上"动态工具 + 审计 + 并发 + 可观测 listener"四件工程化能力，
 * 见 EngineeringAgentConfig。
 * @author hengdboy
 */
public interface EngineeringAgent {

    Result<String> chat(@MemoryId Long sessionId, @UserMessage String question);
}
