package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Ch24 职位（job_position 表）。
 *
 * <p>JD 全文与必备技能是评估 Agent 的输入之一；requiredSkills 同时是
 * canned 评估模式的确定性打分依据。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("job_position")
public class JobPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职位名 */
    private String title;

    /** 部门 */
    private String department;

    /** JD 全文 */
    private String jdText;

    /** 必备技能（JSON 数组字符串） */
    private String requiredSkills;

    /** 招聘人数 */
    private Integer headcount;

    /** OPEN / CLOSED */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
