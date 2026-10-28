package com.example.assistant.observability;

import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Ch22 可观测性：基于 TokenUsage 计算单次调用费用（LangChain4j 原生）。
 *
 * <p>公式：{@code cost = (inputTokens × inputPerMTokens + outputTokens × outputPerMTokens) / 1_000_000}
 *
 * <h2>为什么不在 ChatModelListener 里直接算</h2>
 * <p>Listener 关心"事件触发"，cost 关心"业务计算"——职责分离。
 * 单独抽出 Calculator 便于：
 * <ol>
 *   <li>单元测试（不依赖 Spring 容器）</li>
 *   <li>复用（同 TokenUsage 可被多个 Listener / Tracker 调用）</li>
 *   <li>未来切换定价模型（按月费、按 token 包）不影响调用方</li>
 * </ol>
 *
 * @author langchain4j-tutorial
 * @since v0.19
 */
@Slf4j
@Component
public class TokenCostCalculator {

    /** 单次调用的费用快照（不可变） */
    public record CostSnapshot(
            String modelName,
            int inputTokens,
            int outputTokens,
            int totalTokens,
            double inputCost,
            double outputCost,
            double totalCost,
            String currency) {
    }

    /** 计算单次调用的费用。TokenUsage 为 null 时返回零值快照（不抛异常） */
    public CostSnapshot calculate(String modelName, TokenUsage usage) {
        if (usage == null) {
            return new CostSnapshot(modelName, 0, 0, 0, 0.0, 0.0, 0.0,
                    ModelPricing.of(modelName).getCurrency());
        }
        ModelPricing pricing = ModelPricing.of(modelName);
        int inTok = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int outTok = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;

        double inputCost = inTok * pricing.getInputPerMTokens() / 1_000_000.0;
        double outputCost = outTok * pricing.getOutputPerMTokens() / 1_000_000.0;
        double totalCost = inputCost + outputCost;

        return new CostSnapshot(
                modelName, inTok, outTok, inTok + outTok,
                inputCost, outputCost, totalCost,
                pricing.getCurrency()
        );
    }

    /** 便捷方法：从 ChatModelResponseContext 直接算成本（最常用场景） */
    public CostSnapshot fromResponse(String modelName, TokenUsage usage) {
        return calculate(modelName, usage);
    }
}