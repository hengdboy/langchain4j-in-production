package com.example.assistant.guardrail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ch21 Guardrails：埋点计数器。
 *
 * <p>提供 Guardrail 事件的可观测性指标：
 * <ul>
 *   <li>PII 脱敏次数（按类型：phone / email / idcard / bankcard）</li>
 *   <li>API Key 拦截次数</li>
 *   <li>输入截断次数（用户输入超长）</li>
 *   <li>工具拒绝次数（按工具名）</li>
 *   <li>限流触发次数（每会话）</li>
 * </ul>
 *
 * <h2>为什么不用 Micrometer Counter</h2>
 * <p>{@link Counter} 依赖 {@link MeterRegistry}，但 Ch21 教学版不想强绑 actuator。
 * 本类用 {@link AtomicLong} 自维护 + {@link PostConstruct} 注册到全局 MeterRegistry（如果有）。
 * 既能 standalone 跑，又能接 Micrometer / Prometheus。
 *
 * @author langchain4j-tutorial
 * @since v0.18
 */
@Slf4j
@Component
public class GuardrailMetrics {

    private final ConcurrentHashMap<String, AtomicLong> piiCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolBlockCounters = new ConcurrentHashMap<>();
    private final AtomicLong apiKeyBlocks = new AtomicLong();
    private final AtomicLong inputTruncations = new AtomicLong();
    private final AtomicLong rateLimitHits = new AtomicLong();

    public void incrementPiiRedacted(String type, int count) {
        piiCounters.computeIfAbsent(type, k -> new AtomicLong()).addAndGet(count);
    }

    public void incrementApiKeyBlock() {
        long n = apiKeyBlocks.incrementAndGet();
        if (n == 1 || n % 100 == 0) {
            log.warn("🚨 [Guardrail Metrics] API Key blocks total = {}", n);
        }
    }

    public void incrementInputTruncation() {
        inputTruncations.incrementAndGet();
    }

    public void incrementToolBlock(String toolName) {
        toolBlockCounters.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
    }

    public void incrementRateLimit() {
        rateLimitHits.incrementAndGet();
    }

    /** 导出当前快照（便于 Controller 显示 / 测试断言） */
    public Snapshot snapshot() {
        return new Snapshot(
                new java.util.HashMap<>(piiCounters.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> e.getValue().get()))),
                new java.util.HashMap<>(toolBlockCounters.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> e.getValue().get()))),
                apiKeyBlocks.get(),
                inputTruncations.get(),
                rateLimitHits.get()
        );
    }

    public record Snapshot(
            java.util.Map<String, Long> piiRedacted,
            java.util.Map<String, Long> toolBlocks,
            long apiKeyBlocks,
            long inputTruncations,
            long rateLimitHits) {}
}