package com.example.hiring.core;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * FSM 状态变更流水（ARCHITECTURE.md  lead_event 表）。
 *
 * <p>每次 accepted 状态转换写入一条，用于审计与回溯（v0.25 起落库，
 * 此前只打日志）。
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@Data
@TableName("candidate_event")
public class CandidateEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    /** 变更前状态（新建线索首次转换可为 null） */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 触发事件 */
    private String event;

    /** actor：system / agent:xxx / human:xxx */
    private String actor;

    private String reason;

    /** CandidateContext.extras 快照（JSON 字符串） */
    private String metadata;

    private Instant createdAt;
}
