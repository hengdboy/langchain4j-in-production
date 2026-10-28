package com.example.hiring.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Ch24 API 层：创建职位请求体。
 *
 * @param title          职位名
 * @param department     部门
 * @param jdText         JD 全文
 * @param requiredSkills 必备技能（决策白名单字段，进评估画像）
 * @param headcount      招聘人数
 * @author langchain4j-tutorial
 * @since v0.30
 */
public record CreateJobRequest(
        @NotBlank(message = "title is required") String title,
        String department,
        @NotBlank(message = "jdText is required") String jdText,
        List<String> requiredSkills,
        @NotNull(message = "headcount is required") Integer headcount) {
}
