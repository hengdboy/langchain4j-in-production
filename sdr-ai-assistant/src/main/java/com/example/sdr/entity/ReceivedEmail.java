package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Ch24 阶段五：客户回信（ARCHITECTURE.md §4.6 received_email 表）。
 *
 * <p>回信由收件监听（IMAP/Webhook，教学版用 API 模拟）写入，经
 * {@link com.example.sdr.agent.IntentClassifierAgent} 判级后回填 intentGrade。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Data
@TableName("received_email")
public class ReceivedEmail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;

    private String fromEmail;

    private String subject;

    private String body;

    private Instant receivedAt;

    /** 阶段五判定后回填：A/B/C/D */
    private String intentGrade;
}