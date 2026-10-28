package com.example.sdr.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Ch24 API 层：发送 FSM 事件请求体。
 *
 * <p>可选字段用于注入状态机 Guard 需要的上下文：
 * <ul>
 *   <li>{@code intentGrade} — EMAIL_REPLIED 的 A/B/C/D 分级（Guard 按字母匹配）</li>
 *   <li>{@code smtpVerified} — QUALIFY_OK/FAIL 的 SMTP 验真结果</li>
 *   <li>{@code salesAccepted} — INTENT_B → HANDOFF 需销售认领 true</li>
 * </ul>
 *
 * @param event          FsmEvent 枚举名（如 QUALIFY_OK / CONTENT_READY / EMAIL_SENT ...）
 * @param intentGrade    A/B/C/D（EMAIL_REPLIED 时必填）
 * @param smtpVerified   SMTP 验真结果
 * @param salesAccepted  销售是否认领
 * @author langchain4j-tutorial
 * @since v0.25
 */
public record SendEventRequest(
        @NotBlank(message = "event is required") String event,
        String intentGrade,
        Boolean smtpVerified,
        Boolean salesAccepted) {
}
