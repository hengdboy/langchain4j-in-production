package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 面试安排（interview 表）。
 *
 * <p>候选人确认（INTERVIEW_SCHEDULED）时由 pipeline 自动占位 + 出题；
 * 面试完成后回填 {@link #feedback}（原始反馈）与评价结论。
 *
 * @author hengdboy
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview")
public class Interview {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    /** 面试开始时间 */
    private LocalDateTime slotStart;

    /** 时长（分钟） */
    private Integer durationMinutes;

    /** video / onsite */
    private String mode;

    /** 面试官（教学版固定 interviewer-01） */
    private String interviewer;

    /** 日历预订号（CalendarTool） */
    private String bookingId;

    /** 面试题（JSON 数组字符串） */
    private String questions;

    /** 面试官原始反馈 */
    private String feedback;

    /** BOOKED / DONE / CANCELLED */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
