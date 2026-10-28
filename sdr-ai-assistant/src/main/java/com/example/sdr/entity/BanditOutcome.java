package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Ch24 v0.28 Bandit 待结算队列：每条 lead 在 QUALIFIED 阶段选了一个 arm，
 * 后续 EvolutionScheduler 根据 lead 最终状态计算 reward、写回本表。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Data
@TableName("bandit_outcome")
public class BanditOutcome {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    private String armId;

    /** 选择该臂时的 prompt 上下文（DPO 训练用） */
    private String armPrompt;

    /** 该臂产出的内容（DPO chosen） */
    private String armOutput;

    /** reward 值（0~1；NULL=未结算） */
    private Double reward;

    /** 触发结算的最终状态：INTENT_A/B/C/D/HANDOFF/POOL/DORMANT */
    private String outcomeStatus;

    /** EvolutionScheduler 结算时间（NULL=未结算） */
    private Instant processedAt;

    private Instant createdAt;
}