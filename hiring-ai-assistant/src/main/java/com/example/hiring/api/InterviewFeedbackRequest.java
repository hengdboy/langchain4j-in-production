package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * API 层：面试官提交反馈请求体（阶段四入口）。
 *
 * @param feedback 面试官原始反馈（自由文本；汇总与合规过滤交给 EvaluationAgent + 护栏）
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record InterviewFeedbackRequest(
        @NotBlank(message = "feedback is required") String feedback) {
}
