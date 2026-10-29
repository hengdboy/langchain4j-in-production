package com.example.hiring.evolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThompsonSampling 单元测试（纯算法，无 DB）。
 *
 * @author hengdboy
 * @since v0.28
 */
@Slf4j
@DisplayName("ThompsonSampling 单元测试")
class ThompsonSamplingTest {

    @Test
    @DisplayName("【冷启动】所有臂 totalPulls < minPulls → 轮转探索")
    void testColdStartRoundRobin() {
        ThompsonSampling ts = new ThompsonSampling(2);
        Map<String, Integer> counts = new HashMap<>();
        List<BanditStrategy.ArmState> arms = List.of(
                new BanditStrategy.ArmState("a", 1.0, 1.0, 0L, 0.0),
                new BanditStrategy.ArmState("b", 1.0, 1.0, 0L, 0.0),
                new BanditStrategy.ArmState("c", 1.0, 1.0, 0L, 0.0));
        for (int i = 0; i < 6; i++) {
            String arm = ts.selectArm(arms);
            counts.merge(arm, 1, Integer::sum);
        }
        // 每个臂应至少被选 2 次（minPulls=2 → 每个臂 2 次后退出轮转；6 次调用恰好每臂 2 次）
        assertThat(counts).containsKeys("a", "b", "c");
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(6);
        log.info("冷启动轮转：{}", counts);
    }

    @Test
    @DisplayName("【收敛】臂 a 高 reward → 经过多次 update 后 a 被优先选")
    void testConvergence() {
        ThompsonSampling ts = new ThompsonSampling(100);  // 关闭冷启动
        // 臂 a：100 次拉取 + 90 次成功 → alpha=1+90=91, beta=1+10=11
        BanditStrategy.ArmState a = BanditStrategy.ArmState.fromReward("a", 100L, 90.0);
        // 臂 b：100 次拉取 + 10 次成功 → alpha=11, beta=91
        BanditStrategy.ArmState b = BanditStrategy.ArmState.fromReward("b", 100L, 10.0);
        // 反复选 200 次，应几乎全是 a
        Map<String, Integer> counts = new HashMap<>();
        List<BanditStrategy.ArmState> arms = List.of(a, b);
        for (int i = 0; i < 200; i++) {
            String arm = ts.selectArm(arms);
            counts.merge(arm, 1, Integer::sum);
        }
        assertThat(counts.getOrDefault("a", 0)).isGreaterThan(180);
        log.info("收敛：a 胜出 {} / 200", counts.getOrDefault("a", 0));
    }

    @Test
    @DisplayName("【update】reward=1.0 → alpha+1；reward=0 → beta+1")
    void testUpdate() {
        ThompsonSampling ts = new ThompsonSampling();
        BanditStrategy.ArmState a = new BanditStrategy.ArmState("a");
        BanditStrategy.ArmState r1 = ts.update(a, 1.0);
        assertThat(r1.alpha()).isEqualTo(2.0);
        assertThat(r1.beta()).isEqualTo(1.0);
        BanditStrategy.ArmState r2 = ts.update(r1, 0.0);
        assertThat(r2.alpha()).isEqualTo(2.0);
        assertThat(r2.beta()).isEqualTo(2.0);
        assertThat(r2.totalPulls()).isEqualTo(2);
        log.info("update：alpha={} beta={} pulls={}",
                r2.alpha(), r2.beta(), r2.totalPulls());
    }
}