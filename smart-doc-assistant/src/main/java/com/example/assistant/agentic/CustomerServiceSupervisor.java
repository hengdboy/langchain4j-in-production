package com.example.assistant.agentic;

import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import org.springframework.stereotype.Service;

/**
 * 新增：Supervisor 模式（纯 Agentic 自主编排）——客服主管（薄封装）。
 *
 * <p>三个子 Agent 与主管 Agent 的装配见 {@link SupervisorAgentConfig}（{@code @Configuration @Bean}），
 * 这里只负责把 {@link SupervisorAgent} 注入进来并暴露一个提问入口，不再在构造器里手写 new。
 *
 * <p>主管 Agent 拿到用户问题后<b>自主决定</b>接下来调用哪个子 Agent、按什么顺序，
 * 直到任务完成（内部生成计划，类似 "AgentInvocation 列表"），最后用
 * {@code SupervisorResponseStrategy.SUMMARY} 汇总各子 Agent 的结果返回给用户。
 */
@Service
public class CustomerServiceSupervisor {

    private final SupervisorAgent supervisor;

    public CustomerServiceSupervisor(SupervisorAgent supervisor) {
        this.supervisor = supervisor;
    }

    /** 提问 → 主管自主分派 → 汇总答案 */
    public String ask(String question) {
        return supervisor.invoke(question);
    }
}
