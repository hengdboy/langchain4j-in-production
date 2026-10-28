package com.example.hiring.evolution;

import com.example.hiring.entity.BanditOutcome;
import com.example.hiring.core.CandidateContext;
import com.example.hiring.mapper.BanditOutcomeMapper;
import com.example.hiring.mapper.CandidateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进化调度器：周期性结算 {@code bandit_outcome} 队列。
 *
 * <h2>流程</h2>
 * <ol>
 * <li>查 {@code bandit_outcome WHERE processed_at IS NULL}</li>
 * <li>对每条按 {@code candidate_id} 取当前候选人状态 → 算 reward</li>
 * <li>{@link BanditOptimizer#settleOutcome} → 更新 bandit_state</li>
 * <li>同时 {@link DpoSampleWriter#writeBanditSample} 写一条 DPO 对比样本</li>
 * </ol>
 *
 * <p>频率见 {@code hiring.scheduler.bandit-eval-interval-ms}（默认 10 分钟）。
 *
 * @author langchain4j-tutorial
 * @since v0.28 → v0.30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionScheduler {

    private final BanditOutcomeMapper outcomeMapper;
    private final CandidateMapper candidateMapper;
    private final BanditOptimizer optimizer;
    private final DpoSampleWriter dpoWriter;

    @Scheduled(fixedDelayString = "${hiring.scheduler.bandit-eval-interval-ms:600000}")
    public void settle() {
        List<BanditOutcome> unsettled = outcomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BanditOutcome>()
                        .isNull("processed_at")
                        .orderByAsc("id")
                        .last("LIMIT 200"));
        if (unsettled.isEmpty()) {
            return;
        }
        log.info("[EvolutionScheduler] settling {} outcomes", unsettled.size());

        int settled = 0;
        for (BanditOutcome o : unsettled) {
            CandidateContext candidate = candidateMapper.selectById(o.getCandidateId());
            if (candidate == null) {
                log.warn(" [EvolutionScheduler] outcome id={} candidateId={} missing, skip",
                        o.getId(), o.getCandidateId());
                o.setProcessedAt(java.time.Instant.now());
                o.setReward(0.0);
                outcomeMapper.updateById(o);
                continue;
            }
            o.setOutcomeStatus(candidate.getStatus() == null ? null : candidate.getStatus().name());
            optimizer.settleOutcome(o);

            // Bandit 对比 DPO 样本：chosen=本臂产出，rejected=空（教学版占位；
            // 生产应取同 prompt 上一条不同臂的输出做 rejected）
            dpoWriter.writeBanditSample(o.getArmPrompt(), o.getArmOutput(), "",
                    "{\"arm_id\":\"" + o.getArmId() + "\",\"reward\":" + o.getReward() + "}");
            settled++;
        }
        log.info("[EvolutionScheduler] settled {} outcomes", settled);
    }
}
