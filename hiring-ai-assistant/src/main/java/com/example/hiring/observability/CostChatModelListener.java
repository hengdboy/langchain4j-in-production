package com.example.hiring.observability;

import com.example.hiring.entity.AgentCall;
import com.example.hiring.mapper.AgentCallMapper;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * v0.29 Ch22 Observability：Cost / Latency / Error 计量 listener。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>在每次 ChatModel 请求前写入 {@code AgentCallContext}（ThreadLocal，存开始时间 + agent 名）</li>
 *   <li>响应后计算耗时 + 拿 TokenUsage → 落 {@code agent_call} 表</li>
 *   <li>出错也落表（status=failed），便于事后归因</li>
 * </ul>
 *
 * <h2>Agent 名称来源</h2>
 * <p>{@link AgentCallContext}（ThreadLocal）由 {@code CandidatePipelineService} 在调用 Agent 前
 * 设置。{@code ChatModelListener} 在 LangChain4j 内部回调链中可能跨线程，因此 {@code agentName}
 * 也通过 {@code requestContext.attributes()} 兜底传递（{@code AgentBuilder} 注册 listener 时注入）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostChatModelListener implements ChatModelListener {

    /** 属性 key：Agent 名（AgentBuilder 注入） */
    public static final String ATTR_AGENT_NAME = "sdr.agent.name";

    private final AgentCallMapper agentCallMapper;

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 记录开始时间（跨 onResponse 计算 latency）
        ctx.attributes().put("sdr.startTime", Instant.now());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Long candidateId = AgentCallContext.currentCandidateId();
        String agentName = resolveAgentName(ctx);
        TokenUsage usage = ctx.chatResponse().metadata().tokenUsage();

        AgentCall call = new AgentCall();
        call.setCandidateId(candidateId);
        call.setAgentName(agentName);
        call.setStatus("success");
        if (usage != null) {
            call.setInputTokens(usage.inputTokenCount());
            call.setOutputTokens(usage.outputTokenCount());
            call.setTotalTokens(usage.totalTokenCount());
        }
        call.setDurationMs(elapsedMsFromAttributes(ctx.attributes()));
        call.setCreatedAt(Instant.now());
        try {
            agentCallMapper.insert(call);
            log.info("📊 [Cost] agent={} candidateId={} tokens={} duration_ms={}",
                    agentName, candidateId, usage != null ? usage.totalTokenCount() : 0, call.getDurationMs());
        } catch (Exception e) {
            // 埋点失败不能阻塞主流程
            log.warn("⚠️ [Cost] persist agent_call failed: {}", e.getMessage());
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Long candidateId = AgentCallContext.currentCandidateId();
        String agentName = resolveAgentName(ctx);

        AgentCall call = new AgentCall();
        call.setCandidateId(candidateId);
        call.setAgentName(agentName);
        call.setStatus("failed");
        call.setErrorMessage(truncate(ctx.error().getMessage(), 1000));
        call.setDurationMs(elapsedMsFromAttributes(ctx.attributes()));
        call.setCreatedAt(Instant.now());
        try {
            agentCallMapper.insert(call);
        } catch (Exception e) {
            log.warn("⚠️ [Cost] persist error agent_call failed: {}", e.getMessage());
        }
        log.warn("⚠️ [Cost] agent={} candidateId={} error={}", agentName, candidateId, ctx.error().getMessage());
    }

    /** Agent 名从 attributes（高优） / ThreadLocal（兜底）取 */
    private String resolveAgentName(ChatModelResponseContext ctx) {
        Object attr = ctx.attributes().get(ATTR_AGENT_NAME);
        if (attr instanceof String s && !s.isBlank()) return s;
        String fromTl = AgentCallContext.currentAgentName();
        return fromTl != null ? fromTl : "unknown";
    }

    private String resolveAgentName(ChatModelErrorContext ctx) {
        Object attr = ctx.attributes().get(ATTR_AGENT_NAME);
        if (attr instanceof String s && !s.isBlank()) return s;
        String fromTl = AgentCallContext.currentAgentName();
        return fromTl != null ? fromTl : "unknown";
    }

    private static Integer elapsedMsFromAttributes(Map<Object, Object> attributes) {
        Object start = attributes.get("sdr.startTime");
        if (start instanceof Instant s) {
            return (int) Duration.between(s, Instant.now()).toMillis();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}