package com.example.lab;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Lab3 售后 Agent：声明式接口 + 工具 + 会话记忆。
 *
 * <p>系统提示里写死业务规则（先查订单再查政策、500 元上限直接转人工、食品特殊），
 * 模型负责"怎么走流程"，工具负责"能不能做"——护栏在工具层兜底，
 * 模型被注入也得撞在 {@link RefundGuard} 上。
 * @author hengdboy
 */
public final class AfterSaleAgentService {

    private final Assistant assistant;

    public AfterSaleAgentService(ChatModel model) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(new AfterSaleTools())
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /** 带会话隔离的入口：memoryId 不同则互不串话（多用户场景的最低要求）。 */
    public String chat(String sessionId, String userMessage) {
        InjectionDetector.requireClean(userMessage);   // Application 层先过注入检测
        return assistant.chat(sessionId, userMessage);
    }

    interface Assistant {
        @SystemMessage("""
                你是电商售后客服 Agent。处理用户的退货退款请求，严格遵守：
                1. 先调 queryOrder 查订单，再调 checkReturnPolicy 查政策，然后给结论；
                2. 退款金额超过 500 元时不要尝试 submitRefund（会被拦截），直接调 requestHuman 转人工；
                3. 食品类目非质量问题不支持退款，要向用户解释原因；
                4. 回答用户时给出明确的办理结果或下一步，金额用元表述。
                """)
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }
}
