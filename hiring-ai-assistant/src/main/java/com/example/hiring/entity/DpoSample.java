package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DPO 对齐样本（dpo_sample 表）。
 *
 * <p>两个来源：Bandit 对比样本（自动）、HR 人工修正（手动）。
 * 累积到 {@code hiring.bandit.retrain-threshold} 后供离线重训。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("dpo_sample")
public class DpoSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String prompt;

    /** 人工标注"更好的版本" */
    private String chosen;

    /** 人工标注"较差的版本" */
    private String rejected;

    /** human / bandit / feedback */
    private String source;

    private String metadata;

    private Boolean usedInTraining;

    private Instant createdAt;
}
