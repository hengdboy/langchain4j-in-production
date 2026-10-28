package com.example.sdr.evolution;

import java.util.List;

/**
 * Ch24 v0.28 Bandit 策略接口。
 *
 * <h2>支持的实现</h2>
 * <ul>
 *   <li>{@link ThompsonSampling}（默认；冷启动友好）</li>
 *   <li>UCB1、epsilon-greedy（预留接口位）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
public interface BanditStrategy {

    /**
     * 从臂列表中选一个 arm_id（每个臂一个 Beta(alpha, beta) 分布）。
     */
    String selectArm(List<ArmState> arms);

    /**
     * 根据 reward 更新指定臂的分布（reward ∈ [0, 1]）。
     */
    ArmState update(ArmState arm, double reward);

    /**
     * 臂状态（Beta 分布参数）。
     */
    record ArmState(String armId, double alpha, double beta, long totalPulls, double totalReward) {
        public ArmState(String armId) {
            this(armId, 1.0, 1.0, 0L, 0.0);  // 均匀先验
        }
        /** 从 totalReward/totalPulls 推算的便捷构造：alpha=1+success, beta=1+fail */
        public static ArmState fromReward(String armId, long pulls, double rewardSum) {
            double alpha = 1.0 + rewardSum;
            double beta = 1.0 + (pulls - (long) rewardSum);
            return new ArmState(armId, alpha, beta, pulls, rewardSum);
        }
    }
}