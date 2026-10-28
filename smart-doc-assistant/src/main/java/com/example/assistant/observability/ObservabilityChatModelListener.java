package com.example.assistant.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 可观测性：<strong>核心</strong>——{@link ChatModelListener} 实现，
 * 把 LangChain4j 每次模型调用的 request/response/error 全量数据
 * + TokenUsage 成本核算 串联起来。
 *
 * <p>这是  全部逻辑的"心脏"——其他类（{@link CostTracker}、{@link TokenCostCalculator}、
 * {@link MetricsController}）都是它的下游。
 *
 * <h2>attributes 跨方法传数据</h2>
 * <p>对 streaming 调用，{@code onResponse} 跑在<strong>另一个线程</strong>，
 * 无法靠 ThreadLocal 共享数据；唯一可靠的通道是 {@code attributes} map。
 * 本类用 {@code "startNanos"} 在 onRequest 时记录，onResponse/onError 时算耗时。
 *
 * <h2>与官方文档的呼应</h2>
 * <ul>
 * <li>onRequest 一次请求一次（重试不重复触发）——官方原文</li>
 * <li>多个 ChatModelListener 按 List 迭代顺序执行——同顺序</li>
 * <li>异常从 listener 方法抛出只 WARN，不中断后续 listener——同</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.19
 */
@Slf4j
@Component
public class ObservabilityChatModelListener implements ChatModelListener {

    /** attributes map 的 key：调用开始时间（用于算耗时） */
    private static final String ATTR_START_NANOS = "ch22.startNanos";

    /** attributes map 的 key：调用者 userId（从外部设置） */
    private static final String ATTR_USER_ID = "ch22.userId";

    /** attributes map 的 key：关联的工具名（从外部设置） */
    private static final String ATTR_TOOL_NAME = "ch22.toolName";

    private final TokenCostCalculator costCalculator;
    private final CostTracker costTracker;

    public ObservabilityChatModelListener(TokenCostCalculator costCalculator, CostTracker costTracker) {
        this.costCalculator = costCalculator;
        this.costTracker = costTracker;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        // 记录开始时间——通过 attributes 传到 onResponse/onError（streaming 跨线程安全）
        ctx.attributes().put(ATTR_START_NANOS, System.nanoTime());
        ChatRequest req = ctx.chatRequest();
        log.info("[ChatModel] → onRequest model={} messages={} tools={}",
                ctx.modelProvider(),
                req.messages() != null ? req.messages().size() : 0,
                req.parameters() != null && req.parameters().toolSpecifications() != null
                        ? req.parameters().toolSpecifications().size() : 0);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        // 算耗时：onRequest 在 A 线程，onResponse 在 B 线程（streaming），attributes 唯一可靠
        Long startNanos = (Long) ctx.attributes().get(ATTR_START_NANOS);
        long elapsedMs = startNanos != null ? (System.nanoTime() - startNanos) / 1_000_000 : -1;

        ChatResponse resp = ctx.chatResponse();
        String modelName = resp.metadata() != null ? resp.metadata().modelName() : null;
        TokenUsage usage = resp.metadata() != null ? resp.metadata().tokenUsage() : null;

        // 1. 成本核算（官方 TokenUsage → 业务 CostSnapshot）
        TokenCostCalculator.CostSnapshot cost = costCalculator.fromResponse(modelName, usage);

        // 2. 写入 CostTracker（按维度聚合）
        String userId = (String) ctx.attributes().get(ATTR_USER_ID);
        String toolName = (String) ctx.attributes().get(ATTR_TOOL_NAME);
        costTracker.record(cost, userId, toolName);

        // 3. 结构化日志
        log.info("[ChatModel] ← onResponse model={} elapsedMs={} in={} out={} cost={}{}",
                modelName, elapsedMs,
                cost.inputTokens(), cost.outputTokens(),
                String.format("%.6f", cost.totalCost()), cost.currency());
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Long startNanos = (Long) ctx.attributes().get(ATTR_START_NANOS);
        long elapsedMs = startNanos != null ? (System.nanoTime() - startNanos) / 1_000_000 : -1;
        Map<Object, Object> attrs = ctx.attributes();
        log.warn("[ChatModel] onError model={} elapsedMs={} error={} attributes={}",
                ctx.modelProvider(), elapsedMs,
                ctx.error().getClass().getSimpleName() + ": " + ctx.error().getMessage(),
                attrs);
    }
}