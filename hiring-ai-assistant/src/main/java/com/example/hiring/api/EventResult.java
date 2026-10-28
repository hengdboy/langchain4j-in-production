package com.example.hiring.api;

import com.example.hiring.core.CandidateContext;
import com.example.hiring.core.CandidateStatus;

/**
 * Ch24 API 层：事件处理结果。
 *
 * @param accepted   是否被状态机接受（状态发生变化）
 * @param fromStatus 变更前状态
 * @param toStatus   变更后状态
 * @param candidate  处理后的候选人（含最新 status / matchScore / inviteRounds）
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record EventResult(
        boolean accepted,
        CandidateStatus fromStatus,
        CandidateStatus toStatus,
        CandidateContext candidate) {
}
