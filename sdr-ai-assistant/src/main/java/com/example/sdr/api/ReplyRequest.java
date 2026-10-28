package com.example.sdr.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Ch24 API 层：客户回信请求体。
 *
 * <p>阶段五入口：模拟收件监听（IMAP/Webhook）收到回信，
 * 由 {@link com.example.sdr.service.LeadPipelineService} 调 IntentClassifierAgent 判级
 * 后触发 {@code EMAIL_REPLIED(intentGrade)}。
 *
 * @param text 回信正文
 * @author langchain4j-tutorial
 * @since v0.26
 */
public record ReplyRequest(
        @NotBlank(message = "text is required") String text) {
}
