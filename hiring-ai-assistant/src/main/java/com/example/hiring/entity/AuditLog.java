package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 合规审计（audit_log 表）。
 *
 * <p>招聘场景的审计比一般业务更硬：每一次 AI 参与的人事决策都要能回答
 * "为什么拒了他"——字段粒度的决策留痕见 candidate.matchReasons，
 * 本表记录操作级流水（谁在何时对哪个候选人做了什么）。
 *
 * @author hengdboy
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** system / agent:xxx / human:xxx */
    private String actor;

    /** create / read / update / delete / export / decide */
    private String action;

    /** candidate / job / interview / dpo_sample */
    private String resourceType;

    private Long resourceId;

    private Long candidateId;

    private String detail;

    private String ipAddress;

    private String userAgent;

    private Instant createdAt;
}
