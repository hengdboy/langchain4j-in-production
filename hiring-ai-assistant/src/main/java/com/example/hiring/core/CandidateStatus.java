package com.example.hiring.core;

/**
 * Ch24 FSM 状态机：候选人状态枚举（ARCHITECTURE.md §3.1）。
 *
 * <h2>12 状态分类</h2>
 * <ul>
 *   <li><b>流程中（6 个）</b>：APPLIED → SHORTLISTED → INVITE_SENT → INTERVIEW_SCHEDULED
 *       → INTERVIEWED → OFFER_SENT</li>
 *   <li><b>待定（1 个）</b>：ON_HOLD（匹配分中间带，等 HR 人工复核）</li>
 *   <li><b>完成（2 个）</b>：HIRED / REJECTED（终态）</li>
 *   <li><b>流失（3 个）</b>：WITHDRAWN / CLOSED_NO_RESPONSE / EXPIRED（终态）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public enum CandidateStatus {

    // ============== 流程中状态 ==============
    /** 已投递（简历入库，等待解析+评估） */
    APPLIED,
    /** 已入围（评估通过，等待约面邀请） */
    SHORTLISTED,
    /** 约面邀请已发出（等待候选人回复 / 48h 超时追发） */
    INVITE_SENT,
    /** 已约面（日历占位成功，等待面试官反馈） */
    INTERVIEW_SCHEDULED,
    /** 已面试（等待评价汇总 → OFFER / 淘汰） */
    INTERVIEWED,
    /** 已发 offer（等待候选人答复 / 5 天超时催办） */
    OFFER_SENT,

    // ============== 待定 ==============
    /** 待定池：匹配分中间带，等 HR 人工复核（复核通过 → SHORTLISTED，否决 → REJECTED） */
    ON_HOLD,

    // ============== 完成状态（终态） ==============
    /** 候选人接受 offer */
    HIRED,
    /** 淘汰（评估不通过 / 面试不通过 / HR 复核否决；拒信必须带理由） */
    REJECTED,

    // ============== 流失状态（终态） ==============
    /** 候选人主动放弃（拒绝约面 / 拒绝 offer） */
    WITHDRAWN,
    /** 追发轮次用完仍无回复 */
    CLOSED_NO_RESPONSE,
    /** offer 超时（催办后仍无答复） */
    EXPIRED;

    /** 是否处于流程中（可推进的活跃状态） */
    public boolean isActive() {
        return this == APPLIED || this == SHORTLISTED || this == INVITE_SENT
                || this == INTERVIEW_SCHEDULED || this == INTERVIEWED || this == OFFER_SENT;
    }

    /** 是否在等待候选人回复（48h / 5d 延迟事件挂在这两个状态上） */
    public boolean isAwaitingReply() {
        return this == INVITE_SENT || this == OFFER_SENT;
    }

    /** 是否为待定池 */
    public boolean isOnHold() {
        return this == ON_HOLD;
    }

    /** 是否为终态（不再转换） */
    public boolean isTerminal() {
        return this == HIRED || this == REJECTED || this == WITHDRAWN
                || this == CLOSED_NO_RESPONSE || this == EXPIRED;
    }
}
