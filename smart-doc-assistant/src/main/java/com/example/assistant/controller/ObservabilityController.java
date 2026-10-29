package com.example.assistant.controller;

import com.example.assistant.observability.CostTracker;
import com.example.assistant.observability.ModelPricing;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 可观测性：自定义 metrics Controller——LangChain4j 原生方式暴露成本数据。
 *
 * <p>三个端点：
 * <ul>
 * <li>{@code GET /api/metrics/tokens}：当前累计 token 与费用</li>
 * <li>{@code GET /api/metrics/tokens/top?dim=user&n=10}：某维度 top-N</li>
 * <li>{@code GET /api/metrics/pricing}：当前定价表（参考用）</li>
 * </ul>
 *
 * <h2>为什么不依赖 Spring Boot Actuator / Prometheus</h2>
 * <p>本套教程定位是 LangChain4j 教学，不是 DevOps 基建——
 * 自建 3 个端点把 LangChain4j 埋点的数据原样吐出，
 * 想接 Prometheus / Grafana 时直接读取 {@link CostTracker#snapshot()} 即可。
 *
 * @author hengdboy
 * @since v0.19
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class ObservabilityController {

    private final CostTracker costTracker;

    @GetMapping("/tokens")
    public Map<String, Object> tokensSnapshot() {
        CostTracker.Snapshot snap = costTracker.snapshot();
        return Map.of(
                "totalTokens", snap.totalTokens(),
                "totalCost", snap.totalCost(),
                "tokensByModel", snap.tokensByModel(),
                "tokensByUser", snap.tokensByUser(),
                "tokensByTool", snap.tokensByTool()
        );
    }

    @GetMapping("/tokens/top")
    public Map<String, Object> topN(
            @RequestParam(defaultValue = "user") String dim,
            @RequestParam(defaultValue = "10") int n) {
        List<Map.Entry<String, Long>> top = switch (dim) {
            case "user" -> costTracker.topUsers(n);
            case "tool" -> costTracker.topTools(n);
            case "model" -> costTracker.topModels(n);
            default -> throw new IllegalArgumentException("dim must be one of: user | tool | model");
        };
        return Map.of("dim", dim, "n", n, "top", top);
    }

    @GetMapping("/pricing")
    public Map<String, Object> pricingTable() {
        return Map.of(
                "models", java.util.Arrays.stream(ModelPricing.values())
                        .filter(p -> p != ModelPricing.UNKNOWN)
                        .map(p -> Map.of(
                                "model", p.getModelKey(),
                                "inputPerMTokens", p.getInputPerMTokens(),
                                "outputPerMTokens", p.getOutputPerMTokens(),
                                "currency", p.getCurrency()
                        )).toList()
        );
    }
}