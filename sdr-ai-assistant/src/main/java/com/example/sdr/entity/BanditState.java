package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Ch24 v0.28 Bandit 多臂老虎机状态（Thompson Sampling Beta 分布参数）。
 *
 * <p>每条记录 = 一个"臂"的 Beta(alpha, beta) 分布参数；启动时 loadAll 加载到内存，
 * selectArm 时更新、update 时同步落库。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Data
@TableName("bandit_state")
public class BanditState {

    @TableId(type = IdType.INPUT)
    private String armId;

    /** Beta 分布 alpha（成功+1） */
    private Double alpha;

    /** Beta 分布 beta（失败+1） */
    private Double beta;

    /** 总拉取次数 */
    private Long totalPulls;

    /** 累计 reward（用于调回率指标） */
    private Double totalReward;

    private Instant lastUpdated;
}