package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Ch24 v0.28 DPO 对齐样本（bandit / human / feedback 来源）。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Data
@TableName("dpo_sample")
public class DpoSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String prompt;

    private String chosen;

    private String rejected;

    /** human / bandit / feedback */
    private String source;

    private String metadata;

    private Boolean usedInTraining;

    private Instant createdAt;
}