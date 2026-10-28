package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * API 层：候选人投递请求体。
 *
 * <p>投递后 status=APPLIED，pipeline 自动推进：解析 → 决策脱敏 → 评估 →
 * SCREEN_PASS / SCREEN_REVIEW / SCREEN_FAIL。
 *
 * <h2>注意</h2>
 * <p>{@code resumeText} 是含 PII 的简历原文——它只存库（数据不出内网的教学讨论点）
 * 和喂给解析 Agent，<b>永远不直接进评估 prompt</b>。
 *
 * @param email      候选人邮箱（必填，表内唯一）
 * @param name       姓名（可选，缺省由简历解析回填）
 * @param phone      电话（可选，缺省由简历解析回填）
 * @param jobId      应聘职位 ID（必填）
 * @param resumeText 简历原文（必填）
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record CreateCandidateRequest(
        @NotBlank(message = "email is required") String email,
        String name,
        String phone,
        @NotNull(message = "jobId is required") Long jobId,
        @NotBlank(message = "resumeText is required") String resumeText) {
}
