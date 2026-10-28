package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Ch24 阶段四：邮件发送历史（ARCHITECTURE.md §4.5 sent_email 表）。
 *
 * <p>每次外发（首发/追发）落一条，记录正文、语种、SMTP 消息 ID，用于审计与追发复用。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Data
@TableName("sent_email")
public class SentEmail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    /** 第几轮（1 首发，2/3 追发） */
    private Integer roundNo;

    private String fromEmail;

    private String toEmail;

    private String subject;

    private String body;

    private String language;

    private String smtpMessageId;

    /** sent / bounced / opened */
    private String status;

    private Instant sentAt;
}