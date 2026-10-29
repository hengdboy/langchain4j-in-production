package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 候选人回信（received_email 表）。
 *
 * <p>教学版通过 API 模拟回信注入（Webhook/IMAP 拉取的生产接法见 ARCHITECTURE.md ）。
 *
 * @author hengdboy
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("received_email")
public class ReceivedEmail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    private String fromEmail;

    private String subject;

    private String body;

    private Instant receivedAt;

    /** 分类结果：confirmed / declined（ReplyClassifierAgent 回填） */
    private String decision;
}
