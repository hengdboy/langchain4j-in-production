package com.example.assistant.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.langchain4j.model.output.TokenUsage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch22：定价表 + 成本换算实测（2026-10-28 数据的固化版）。
 *
 * <p>关键语义：listener 读的是响应里的模型名——DeepSeek 服务端把
 * {@code deepseek-v4-flash} 归一化成 {@code deepseek-flash} 回传，别名必须进表，
 * 否则成本恒 0（Ch22 踩坑清单 #10）。
 *
 * @author hengdboy
 * @since v0.19
 */
@DisplayName("ModelPricing / TokenCostCalculator：成本换算")
class ModelPricingTest {

    private final TokenCostCalculator calc = new TokenCostCalculator();

    @Test
    @DisplayName("服务端归一化名 deepseek-flash 命中 deepseek-v4-flash 同价")
    void serverNormalizedNameResolved() {
        assertThat(ModelPricing.of("deepseek-flash")).isEqualTo(ModelPricing.DEEPSEEK_V4_FLASH_ALIAS);
        assertThat(ModelPricing.of("deepseek-flash").getInputPerMTokens()).isEqualTo(1.0);
        assertThat(ModelPricing.of("deepseek-flash").getOutputPerMTokens()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("未知模型落 UNKNOWN（单价 0）——成本恒零的病灶")
    void unknownModelFallsToZero() {
        assertThat(ModelPricing.of("some-new-model")).isEqualTo(ModelPricing.UNKNOWN);
        assertThat(ModelPricing.of(null)).isEqualTo(ModelPricing.UNKNOWN);
    }

    @Test
    @DisplayName("成本公式：51×1.0 + 117×2.0 = 285 微元 = ¥0.000285（Ch22 实测单次）")
    void costMath() {
        TokenCostCalculator.CostSnapshot snap = calc.calculate("deepseek-flash",
                new TokenUsage(51, 117));
        assertThat(snap.totalTokens()).isEqualTo(168);
        assertThat(String.format("%.6f", snap.totalCost())).isEqualTo("0.000285");
        assertThat(snap.currency()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("TokenUsage 为 null 时返回零值快照（不抛异常，踩坑清单 #4）")
    void nullUsageReturnsZeroSnapshot() {
        TokenCostCalculator.CostSnapshot snap = calc.calculate("deepseek-flash", null);
        assertThat(snap.totalTokens()).isZero();
        assertThat(snap.totalCost()).isZero();
    }
}
