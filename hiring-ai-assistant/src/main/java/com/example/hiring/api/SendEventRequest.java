package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Ch24 API 层：发送 FSM 事件请求体（调试/教学用直通入口）。
 *
 * <p>可选字段用于注入状态机 Guard 需要的上下文：
 * <ul>
 *   <li>{@code verdict} — SCREEN_PASS/REVIEW/FAIL 的评估结论</li>
 *   <li>{@code replyDecision} — INVITE_REPLIED 的 confirmed/declined</li>
 *   <li>{@code evalOutcome} — EVAL_PASS/FAIL 的 PASS/FAIL</li>
 *   <li>{@code offerDecision} — OFFER_REPLIED 的 accepted/declined</li>
 *   <li>{@code humanApproved} — ON_HOLD 复核结果</li>
 * </ul>
 *
 * @param event         FsmEvent 枚举名（如 SCREEN_PASS / INVITE_REPLIED ...）
 * @param verdict       评估结论
 * @param replyDecision 回复决定
 * @param evalOutcome   评价结论
 * @param offerDecision offer 答复
 * @param humanApproved HR 复核结果
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record SendEventRequest(
        @NotBlank(message = "event is required") String event,
        String verdict,
        String replyDecision,
        String evalOutcome,
        String offerDecision,
        Boolean humanApproved) {
}
