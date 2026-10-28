package com.example.hiring.evolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 Bandit reward 映射单元测试（纯静态方法，无 Spring）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
class BanditOptimizerTest {

    @Test
    @DisplayName("【reward】招聘漏斗越深 reward 越高，流失为 0")
    void testRewardMapping() {
        assertThat(BanditOptimizer.rewardFor("HIRED")).isEqualTo(1.0);
        assertThat(BanditOptimizer.rewardFor("OFFER_SENT")).isEqualTo(0.8);
        assertThat(BanditOptimizer.rewardFor("INTERVIEWED")).isEqualTo(0.6);
        assertThat(BanditOptimizer.rewardFor("INTERVIEW_SCHEDULED")).isEqualTo(0.5);
        assertThat(BanditOptimizer.rewardFor("SHORTLISTED")).isEqualTo(0.3);
        assertThat(BanditOptimizer.rewardFor("REJECTED")).isZero();
        assertThat(BanditOptimizer.rewardFor("WITHDRAWN")).isZero();
        assertThat(BanditOptimizer.rewardFor("CLOSED_NO_RESPONSE")).isZero();
        assertThat(BanditOptimizer.rewardFor("EXPIRED")).isZero();
        assertThat(BanditOptimizer.rewardFor("ON_HOLD")).isZero();
        assertThat(BanditOptimizer.rewardFor(null)).isZero();
        assertThat(BanditOptimizer.rewardFor("UNKNOWN")).isZero();
    }

    @Test
    @DisplayName("【knownArms】默认 6 臂与模板臂空间一致")
    void testKnownArms() {
        assertThat(new BanditOptimizer(null, null).knownArms())
                .containsExactlyInAnyOrderElementsOf(CannedInviteTemplates.ARM_IDS);
    }
}
