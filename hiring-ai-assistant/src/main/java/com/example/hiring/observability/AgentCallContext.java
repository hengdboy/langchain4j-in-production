package com.example.hiring.observability;

/**
 * v0.29 Observability：Agent 调用上下文（ThreadLocal）。
 *
 * <p>在 Agent 调用前由 {@code CandidatePipelineService} 设置；{@code CostChatModelListener} 读取。
 * ThreadLocal 是因为 LangChain4j 的 ChatModelListener 回调发生在调用线程，
 * 而我们的 Agent 是阻塞同步调用，线程不会切换。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
public final class AgentCallContext {

    private static final ThreadLocal<Long> CANDIDATE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_NAME = new ThreadLocal<>();

    private AgentCallContext() {}

    public static void set(Long candidateId, String agentName) {
        CANDIDATE_ID.set(candidateId);
        AGENT_NAME.set(agentName);
    }

    public static void clear() {
        CANDIDATE_ID.remove();
        AGENT_NAME.remove();
    }

    public static Long currentCandidateId() {
        return CANDIDATE_ID.get();
    }

    public static String currentAgentName() {
        return AGENT_NAME.get();
    }
}