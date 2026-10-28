package com.example.sdr.observability;

/**
 * v0.29 Observability：Agent 调用上下文（ThreadLocal）。
 *
 * <p>在 Agent 调用前由 {@code LeadPipelineService} 设置；{@code CostChatModelListener} 读取。
 * ThreadLocal 是因为 LangChain4j 的 ChatModelListener 回调发生在调用线程，
 * 而我们的 Agent 是阻塞同步调用，线程不会切换。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
public final class AgentCallContext {

    private static final ThreadLocal<Long> LEAD_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_NAME = new ThreadLocal<>();

    private AgentCallContext() {}

    public static void set(Long leadId, String agentName) {
        LEAD_ID.set(leadId);
        AGENT_NAME.set(agentName);
    }

    public static void clear() {
        LEAD_ID.remove();
        AGENT_NAME.remove();
    }

    public static Long currentLeadId() {
        return LEAD_ID.get();
    }

    public static String currentAgentName() {
        return AGENT_NAME.get();
    }
}