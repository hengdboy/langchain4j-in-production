package com.example.assistant.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 新增：时间工具
 *
 * 演示：基本 @Tool 注解 + @P 参数描述
 */
public class TimeTools {

    @Tool("获取当前日期和时间，返回 ISO 8601 格式字符串")
    public String currentTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool("计算两个日期之间相差的天数")
    public long daysBetween(
            @P("开始日期，格式 yyyy-MM-dd，如 2026-01-01") String start,
            @P("结束日期，格式 yyyy-MM-dd，如 2026-08-20") String end
    ) {
        try {
            return ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(start),
                java.time.LocalDate.parse(end)
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "日期格式错误：" + e.getMessage() + "。请使用 yyyy-MM-dd 格式。"
            );
        }
    }
}