package com.example.hiring.api;

import jakarta.validation.constraints.NotNull;

/**
 * API 层：HR 对 ON_HOLD 候选人的人工复核。
 *
 * <p>approve=true → SCREEN_PASS(humanApproved) → SHORTLISTED → 自动进入约面流程；
 * approve=false → SCREEN_FAIL(humanApproved) → REJECTED（必须给 reason，进拒信）。
 *
 * @param approve 是否通过
 * @param reason  复核意见（否决时必填，落审计）
 * @author hengdboy
 * @since v0.30
 */
public record ReviewRequest(
        @NotNull(message = "approve is required") Boolean approve,
        String reason) {
}
