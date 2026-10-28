package com.example.sdr.api;

import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadStatus;

/**
 * Ch24 API 层：事件处理结果。
 *
 * @param accepted   是否被状态机接受（from != to 为 accepted；自循环如 TIMEOUT_72H 不改变状态）
 * @param fromStatus 变更前状态
 * @param toStatus   变更后状态
 * @param lead       处理后的 Lead（含最新 status / sentRounds / intentGrade）
 * @author langchain4j-tutorial
 * @since v0.25
 */
public record EventResult(
        boolean accepted,
        LeadStatus fromStatus,
        LeadStatus toStatus,
        LeadContext lead) {
}
