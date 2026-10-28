package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Ch24 邮件发送历史（sent_email 表）。
 *
 * <p>round 语义：1=首发邀请、2=追发、0=确认函/催办函等事务邮件。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sent_email")
public class SentEmail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    /** 第几轮（0=事务邮件，1=首发邀请，2=追发） */
    private Integer roundNo;

    private String fromEmail;

    private String toEmail;

    private String subject;

    private String body;

    private String language;

    /** 网关响应 ID（MailGateway mock） */
    private String smtpMessageId;

    /** sent / bounced / opened */
    private String status;

    private Instant sentAt;
}
