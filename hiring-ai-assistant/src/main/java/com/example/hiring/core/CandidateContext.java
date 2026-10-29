package com.example.hiring.core;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 共享状态：候选人（ARCHITECTURE.md  candidate 表 +  状态机）。
 *
 * <h2>设计</h2>
 * <ul>
 * <li><b>实体即业务</b>：MyBatis-Plus 注解映射到 {@code candidate} 表</li>
 * <li><b>状态机 + 业务</b>：{@link #status} 是 FSM 当前状态；
 * {@link #inviteRounds} / {@link #offerReminderSent} 支撑延迟事件前置判断</li>
 * <li><b>双画像</b>：{@link #resumeText} 是含 PII 的简历原文（只进库不出决策）；
 * {@link #redactedProfile} 是经 {@code DecisionFieldFilter} 剔除受保护特征后的
 * 决策画像——评估 Agent 只看得见后者（Ch25 歧视防护核心）</li>
 * <li><b>Agent 共享</b>：{@link #extras}（JSON 字段）跨 Agent 传临时数据</li>
 * </ul>
 *
 * @author hengdboy
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("candidate")
public class CandidateContext {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 候选人邮箱（约面/offer 沟通渠道；表内唯一） */
    private String email;

    /** 姓名（简历解析回填） */
    private String name;

    /** 电话（简历解析回填；只用于沟通，不进评估） */
    private String phone;

    /** 应聘职位 ID（job_position 表） */
    private Long jobId;

    /** 当前状态（FSM 状态机驱动） */
    private CandidateStatus status;

    /** 匹配分 0-100（MatchEvaluatorAgent 产出） */
    private Integer matchScore;

    /** 评估结论：SHORTLIST / REVIEW / REJECT */
    private String matchVerdict;

    /** 评估理由列表（JSON 数组字符串；拒信可解释性的数据源） */
    private String matchReasons;

    /** 决策画像（脱敏后 JSON；评估 Agent 的唯一输入） */
    private String redactedProfile;

    /** 简历原文（含 PII；教学版存库并讨论"数据不出内网"，生产建议对象存储+加密） */
    private String resumeText;

    /** 已发邀请轮次（1 = 首发，2 = 追发；与 maxInviteRounds 配合） */
    private Integer inviteRounds;

    /** offer 是否已催办过（5 天超时先催办一次，再超时才 EXPIRED） */
    private Boolean offerReminderSent;

    /** Agent 间共享的临时数据（JSON 字符串） */
    @TableField(value = "extras")
    private String extras;

    private Instant createdAt;

    private Instant updatedAt;

    // ============== 业务辅助方法（不映射到数据库） ==============

    /** offer 是否已催办。用 {@code offerReminded()} 判断，别新写 isOfferReminderSent()（JavaBeans 歧义坑）。 */
    public boolean offerReminded() {
        return Boolean.TRUE.equals(offerReminderSent);
    }

    /** 是否达到最大邀请轮次。 */
    public boolean isMaxRoundsReached(int maxRounds) {
        return inviteRounds != null && inviteRounds >= maxRounds;
    }
}
