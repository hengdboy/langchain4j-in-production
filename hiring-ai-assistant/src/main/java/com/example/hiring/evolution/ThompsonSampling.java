package com.example.hiring.evolution;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v0.28 Thompson Sampling 实现（默认 Bandit 策略）。
 *
 * <h2>算法</h2>
 * <ul>
 * <li>每个臂一个 Beta(alpha, beta) 分布；新臂 alpha=beta=1（均匀先验）</li>
 * <li>{@link #selectArm}：从每个臂的分布采样一个值，选最大的</li>
 * <li>{@link #update}：reward=1 → alpha++; reward=0 → beta++；连续 reward 用 totalReward 累加</li>
 * </ul>
 *
 * <h2>冷启动保护</h2>
 * <p>{@code totalPulls < minPulls} 的臂当次按"轮转"选择（均匀分布优先探索），
 * 避免新臂因先验不足被永远压制。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
public class ThompsonSampling implements BanditStrategy {

    /** 冷启动保护：新臂在累计拉取 < 此阈值前轮转优先 */
    private final int minPulls;

    /** 冷启动轮转游标（线程安全：用 AtomicInteger） */
    private final java.util.concurrent.atomic.AtomicInteger coldStartCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public ThompsonSampling() {
        this(5);
    }

    public ThompsonSampling(int minPulls) {
        this.minPulls = minPulls;
    }

    @Override
    public String selectArm(List<ArmState> arms) {
        if (arms == null || arms.isEmpty()) {
            throw new IllegalArgumentException("no arms");
        }

        // 冷启动保护：有未达 minPulls 的臂则轮转优先（按 cursor 取）
        java.util.List<ArmState> cold = arms.stream()
                .filter(a -> a.totalPulls() < minPulls)
                .toList();
        if (!cold.isEmpty()) {
            int idx = Math.floorMod(coldStartCursor.getAndIncrement(), cold.size());
            return cold.get(idx).armId();
        }

        // Thompson Sampling：从每个臂的 Beta 分布采样，选最大
        return arms.stream().max(
                (a, b) -> Double.compare(sampleBeta(a.alpha(), a.beta()),
                                          sampleBeta(b.alpha(), b.beta())))
                .map(ArmState::armId)
                .orElseThrow();
    }

    @Override
    public ArmState update(ArmState arm, double reward) {
        double r = Math.max(0.0, Math.min(1.0, reward));
        double newAlpha = arm.alpha() + r;
        double newBeta = arm.beta() + (1.0 - r);
        return new ArmState(arm.armId(), newAlpha, newBeta,
                arm.totalPulls() + 1, arm.totalReward() + r);
    }

    /** 从 Beta(alpha, beta) 采样（Gamma 变量比值近似，教学版够用） */
    private static double sampleBeta(double alpha, double beta) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        return x / (x + y);
    }

    /** Marsaglia & Tsang 2000 — 形状 k ≥ 1 时的 Gamma 采样（k<1 用 Boor's 校正简化） */
    private static double sampleGamma(double shape) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (shape < 1.0) {
            // Boor 校正：Gamma(shape) = Gamma(shape+1) * U^(1/shape)
            return sampleGamma(shape + 1.0) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double v;
            double x;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v;
        }
    }
}