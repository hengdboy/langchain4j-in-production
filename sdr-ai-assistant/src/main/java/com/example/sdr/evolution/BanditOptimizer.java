package com.example.sdr.evolution;

import com.example.sdr.entity.BanditOutcome;
import com.example.sdr.entity.BanditState;
import com.example.sdr.mapper.BanditOutcomeMapper;
import com.example.sdr.mapper.BanditStateMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Ch24 v0.28 Bandit 编排器：在 selectArm / update 两侧落地"算法 ↔ DB"。
 *
 * <h2>数据流</h2>
 * <pre>
 *   selectArm (synchronous)         update (async, EvolutionScheduler)
 *     │                                │
 *     ▼                                ▼
 *   BanditStrategy                    bandit_outcome.reward =
 *     → 选 arm_id                      rewardMapping(lead.status)
 *   bandit_outcome.insert(arm_id)     BanditStrategy.update → bandit_state
 *                                     DpoSampleWriter.write (Bandit 对比样本)
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BanditOptimizer {

    private final BanditStateMapper stateMapper;
    private final BanditOutcomeMapper outcomeMapper;
    private final BanditStrategy strategy = new ThompsonSampling();

    /** 内存缓存：arm_id → ArmState，启动时从 DB 加载 */
    private final Map<String, BanditStrategy.ArmState> armCache = new ConcurrentHashMap<>();

    @PostConstruct
    void loadFromDb() {
        for (BanditState s : stateMapper.selectList(null)) {
            armCache.put(s.getArmId(),
                    new BanditStrategy.ArmState(s.getArmId(), s.getAlpha(), s.getBeta(),
                            s.getTotalPulls(), s.getTotalReward()));
        }
        log.info("🎰 [Bandit] loaded {} arms from DB: {}", armCache.size(), armCache.keySet());
    }

    /**
     * 选一个 arm，并把这次选择记入 {@code bandit_outcome}（待结算队列）。
     *
     * @param leadId   lead ID
     * @param prompt   prompt 上下文（用于 DPO 训练）
     * @param output   臂产出（开发信正文）
     * @return 选中的 arm_id
     */
    public String selectArm(Long leadId, String prompt, String output) {
        // 冷启动：cache 空（PostConstruct 未跑完或 DB 无数据）→ 用默认 6 臂
        List<BanditStrategy.ArmState> arms;
        if (armCache.isEmpty()) {
            arms = knownArms().stream().map(BanditStrategy.ArmState::new).toList();
        } else {
            arms = armCache.values().stream().toList();
        }
        String armId = strategy.selectArm(arms);

        BanditOutcome o = new BanditOutcome();
        o.setLeadId(leadId);
        o.setArmId(armId);
        o.setArmPrompt(prompt);
        o.setArmOutput(output);
        o.setCreatedAt(Instant.now());
        outcomeMapper.insert(o);
        log.info("🎰 [Bandit] leadId={} selected arm={}", leadId, armId);
        return armId;
    }

    /**
     * 根据 outcome 状态计算 reward，更新 bandit_state（同步落库）。
     */
    public void settleOutcome(BanditOutcome outcome) {
        double reward = rewardFor(outcome.getOutcomeStatus());
        BanditStrategy.ArmState before = armCache.computeIfAbsent(outcome.getArmId(),
                k -> new BanditStrategy.ArmState(k));
        BanditStrategy.ArmState after = strategy.update(before, reward);
        armCache.put(after.armId(), after);

        // 落库
        BanditState row = stateMapper.selectById(after.armId());
        if (row == null) row = new BanditState();
        row.setArmId(after.armId());
        row.setAlpha(after.alpha());
        row.setBeta(after.beta());
        row.setTotalPulls(after.totalPulls());
        row.setTotalReward(after.totalReward());
        row.setLastUpdated(Instant.now());
        stateMapper.insertOrUpdate(row);

        outcome.setReward(reward);
        outcome.setProcessedAt(Instant.now());
        outcomeMapper.updateById(outcome);

        log.info("🎰 [Bandit] settled outcome id={} arm={} status={} reward={} α={} β={}",
                outcome.getId(), outcome.getArmId(), outcome.getOutcomeStatus(),
                reward, after.alpha(), after.beta());
    }

    /**
     * 取所有已知 arm 的快照（用于监控 / 测试断言）。
     */
    public List<BanditStrategy.ArmState> snapshot() {
        return armCache.values().stream().toList();
    }

    /** 已知 arm 列表（用于 selectArm 缺省；教学版写死 6 个） */
    public List<String> knownArms() {
        if (armCache.isEmpty()) {
            // 冷启动：首次调用前 armCache 还没加载（PostConstruct 未跑完），返回默认臂集
            return List.of(
                    "formal:quote:email", "casual:quote:email", "concise:quote:email",
                    "formal:sample:email", "casual:sample:email", "concise:sample:email");
        }
        return armCache.keySet().stream().sorted().collect(Collectors.toList());
    }

    /**
     * Reward 映射：lead 最终状态 → 0~1 reward。
     * 生产应按实际业务定义（甚至按段位做分段函数）。
     */
    public static double rewardFor(String status) {
        if (status == null) return 0.0;
        return switch (status) {
            case "HANDOFF" -> 1.0;
            case "INTENT_A" -> 1.0;
            case "INTENT_B" -> 0.6;
            case "INTENT_C" -> 0.2;
            case "INTENT_D" -> 0.0;
            case "POOL" -> 0.0;
            case "DORMANT" -> 0.1;
            default -> 0.0;
        };
    }
}