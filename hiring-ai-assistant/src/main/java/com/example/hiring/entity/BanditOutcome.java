package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Bandit 待结算队列（bandit_outcome 表）。
 *
 * <p>每次选臂插一条；候选人流到终态（或阶段性状态）后由 EvolutionScheduler
 * 按 reward 映射结算，回填 {@link #reward} 与 {@link #processedAt}。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("bandit_outcome")
public class BanditOutcome {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    private String armId;

    /** 选臂时的 prompt 上下文 */
    private String armPrompt;

    /** 该臂产出的内容（邀请邮件正文） */
    private String armOutput;

    /** reward 0~1（NULL=未结算） */
    private Double reward;

    /** 结算时候选人的状态名 */
    private String outcomeStatus;

    private Instant processedAt;

    private Instant createdAt;
}
