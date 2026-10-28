package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Bandit 状态（bandit_state 表，Thompson Sampling Beta 分布参数）。
 *
 * <p>arm_id 格式：{@code tone:cta:channel}，如 {@code warm:calendar-link:email}。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("bandit_state")
public class BanditState {

    /** 臂 ID（主键，非自增） */
    @TableId(type = IdType.INPUT)
    private String armId;

    /** Beta 分布 alpha（成功+1） */
    private Double alpha;

    /** Beta 分布 beta（失败+1） */
    private Double beta;

    private Long totalPulls;

    private Double totalReward;

    private Instant lastUpdated;
}
