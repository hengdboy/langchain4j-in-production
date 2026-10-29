package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;

/**
 * API 层：HR 修正 AI 产出（DPO 偏好样本入口）。
 *
 * <p>典型场景：HR 不认同 AI 的评价汇总，提交"更好的写法"——
 * (prompt, chosen, rejected) 三元组落 {@code dpo_sample} 表，
 * 累积到阈值后供离线重训。
 *
 * @param prompt   原始输入（哪个环节的 prompt）
 * @param chosen   人工认可的版本
 * @param rejected AI 原始输出
 * @author hengdboy
 * @since v0.30
 */
public record CorrectionRequest(
        @NotBlank(message = "prompt is required") String prompt,
        @NotBlank(message = "chosen is required") String chosen,
        @NotBlank(message = "rejected is required") String rejected) {
}
