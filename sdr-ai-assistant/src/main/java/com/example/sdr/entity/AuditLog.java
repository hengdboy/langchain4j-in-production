package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v0.29 Ch16 Audit：{@code audit_log} 表实体（GDPR 合规审计）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Data
@NoArgsConstructor
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** system / user_id / agent:xxx */
    private String actor;

    /** create / read / update / delete / export */
    private String action;

    /** lead / email / dpo_sample */
    private String resourceType;

    private Long resourceId;

    private Long leadId;

    /** 详情（JSON 字符串） */
    private String detail;

    private String ipAddress;
    private String userAgent;

    private Instant createdAt;
}