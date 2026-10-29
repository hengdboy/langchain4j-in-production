package com.example.hiring.core;

/**
 * FSM 状态机：状态转换事件枚举（ARCHITECTURE.md ）。
 *
 * <p>13 个事件。其中 {@link #REPLY_TIMEOUT_48H} 与 {@link #OFFER_TIMEOUT_5D}
 * <b>不直接进状态机</b>——延迟队列触发后，先在
 * {@code CandidateService.handleReplyTimeout / handleOfferTimeout} 做业务前置判断
 * （追发/催办，或转 MAX_ROUNDS_REACHED / OFFER_EXPIRED）。
 * 此前一版项目（TIMEOUT_72H）实测过的教训：Spring StateMachine 的 Guard 拦截
 * 无法从 sendEvent 返回值区分，自循环不能画进状态机。
 *
 * @author hengdboy
 * @since v0.30
 */
public enum FsmEvent {

    /** 简历评估通过（guard: verdict == SHORTLIST）→ SHORTLISTED */
    SCREEN_PASS,

    /** 简历评估待定（guard: verdict == REVIEW）→ ON_HOLD */
    SCREEN_REVIEW,

    /** 简历评估不通过（guard: verdict == REJECT）→ REJECTED */
    SCREEN_FAIL,

    /** 约面邀请邮件已发出 → INVITE_SENT */
    INVITE_SENT,

    /** 候选人回复约面邀请（guard: replyDecision == confirmed / declined） */
    INVITE_REPLIED,

    /** 约面邀请 48h 无回复（延迟队列 → 业务前置判断追发） */
    REPLY_TIMEOUT_48H,

    /** 追发轮次用完仍无回复 → CLOSED_NO_RESPONSE */
    MAX_ROUNDS_REACHED,

    /** 面试完成（面试官提交反馈）→ INTERVIEWED */
    INTERVIEW_DONE,

    /** 面试评价通过（guard: evalOutcome == PASS）→ OFFER_SENT */
    EVAL_PASS,

    /** 面试评价不通过（guard: evalOutcome == FAIL）→ REJECTED */
    EVAL_FAIL,

    /** 候选人答复 offer（guard: offerDecision == accepted / declined） */
    OFFER_REPLIED,

    /** offer 5 天无答复（延迟队列 → 业务前置判断催办） */
    OFFER_TIMEOUT_5D,

    /** offer 催办后仍无答复 → EXPIRED */
    OFFER_EXPIRED;

    /** 是否会触发 Agent 业务逻辑（进入 pipeline 的确定性编排） */
    public boolean triggersAgent() {
        return this == SCREEN_PASS || this == SCREEN_REVIEW || this == SCREEN_FAIL
                || this == INVITE_SENT || this == INVITE_REPLIED
                || this == INTERVIEW_DONE || this == EVAL_PASS || this == EVAL_FAIL
                || this == OFFER_REPLIED;
    }
}
