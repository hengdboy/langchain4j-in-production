package com.example.sdr.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Ch24 API 层：创建线索请求体。
 *
 * <p>创建后 status=RAW，sentRounds=0（阶段一新建线索状态）。
 *
 * @param email          客户邮箱（必填，lead 表唯一键）
 * @param company        公司名
 * @param country        国家代码（ISO 3166-1 alpha-2）
 * @param keyPerson      决策人 KP（阶段二回填）
 * @param keyPersonEmail KP 邮箱（阶段二回填）
 * @param smtpVerified   KP 邮箱 SMTP 验真结果（阶段二回填）
 * @author langchain4j-tutorial
 * @since v0.25
 */
public record CreateLeadRequest(
        @NotBlank(message = "email is required") String email,
        String company,
        String country,
        String keyPerson,
        String keyPersonEmail,
        Boolean smtpVerified) {
}
