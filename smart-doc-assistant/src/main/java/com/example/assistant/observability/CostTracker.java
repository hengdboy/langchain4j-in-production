package com.example.assistant.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Ch22 可观测性：成本聚合器——按维度（用户 / 工具 / 模型）累计 token 与费用。
 *
 * <p>三个独立计数器（ConcurrentHashMap + LongAdder）支持：
 * <ul>
 *   <li>高并发写（LLM 调用密集时无锁竞争）</li>
 *   <li>快照读取（{@link #snapshot()} 不可变视图，外部迭代安全）</li>
 *   <li>按维度 top-N 排序（用于"哪些用户在烧钱"诊断）</li>
 * </ul>
 *
 * <h2>与 Ch16/Ch19 审计的关系</h2>
 * <p>Ch16 审计记"谁调了什么工具"，本类记"谁烧了多少 token"——可以 JOIN 得出"用户 X 调了 N 次工具 Y 共花 ¥X"。
 * 生产建议把这俩数据源写到同一数据库做关联查询。
 *
 * @author langchain4j-tutorial
 * @since v0.19
 */
@Slf4j
@Component
public class CostTracker {

    /** 单维度计数器：key -> LongAdder */
    private final ConcurrentHashMap<String, LongAdder> byModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> byTool = new ConcurrentHashMap<>();

    /** 总累计 */
    private final LongAdder totalTokens = new LongAdder();
    private final LongAdder totalCostMicro = new LongAdder();  // 以"微元"累计避免浮点精度

    public void record(TokenCostCalculator.CostSnapshot cost, String userId, String toolName) {
        byModel.computeIfAbsent(cost.modelName(), k -> new LongAdder())
                .add(cost.totalTokens());
        if (userId != null) {
            byUser.computeIfAbsent(userId, k -> new LongAdder())
                    .add(cost.totalTokens());
        }
        if (toolName != null) {
            byTool.computeIfAbsent(toolName, k -> new LongAdder())
                    .add(cost.totalTokens());
        }
        totalTokens.add(cost.totalTokens());
        // 浮点转 long 累计（×1_000_000 还原）——避免高精度损失
        totalCostMicro.add((long) (cost.totalCost() * 1_000_000));
    }

    /** 不可变快照（外部线程可安全迭代） */
    public Snapshot snapshot() {
        return new Snapshot(
                snapshotMap(byModel),
                snapshotMap(byUser),
                snapshotMap(byTool),
                totalTokens.sum(),
                totalCostMicro.sum() / 1_000_000.0
        );
    }

    /** 取某维度 top-N（按 token 数倒序） */
    public List<Map.Entry<String, Long>> topUsers(int n) {
        return topN(byUser, n);
    }

    public List<Map.Entry<String, Long>> topTools(int n) {
        return topN(byTool, n);
    }

    public List<Map.Entry<String, Long>> topModels(int n) {
        return topN(byModel, n);
    }

    private static Map<String, Long> snapshotMap(ConcurrentHashMap<String, LongAdder> src) {
        Map<String, Long> result = new LinkedHashMap<>();
        src.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    private static List<Map.Entry<String, Long>> topN(ConcurrentHashMap<String, LongAdder> src, int n) {
        return src.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    /** 不可变快照（用于 Controller 返回 / 监控导出） */
    public record Snapshot(
            Map<String, Long> tokensByModel,
            Map<String, Long> tokensByUser,
            Map<String, Long> tokensByTool,
            long totalTokens,
            double totalCost) {
    }
}