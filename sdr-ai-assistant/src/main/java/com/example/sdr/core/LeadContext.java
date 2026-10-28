package com.example.sdr.core;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Ch24 SDR 共享状态：销售线索（ARCHITECTURE.md §4.2 lead 表 + §3 状态机）。
 *
 * <h2>设计</h2>
 * <ul>
 *   <li><b>实体即业务</b>：用 MyBatis-Plus 注解映射到 {@code lead} 表</li>
 *   <li><b>状态机 + 业务</b>：{@link #status} 是 FSM 当前状态，{@link #sentRounds} / {@link #intentGrade} 等支持 FSM 转换</li>
 *   <li><b>Agent 共享</b>：{@link #extras}（JSON 字段）跨 Agent 传临时数据</li>
 * </ul>
 *
 * <h2>与 LangChain4j 协作</h2>
 * <p>每个 Agent 接收 / 返回此对象；StateMachine Persist 存 Redis 时序列化整个对象。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("`lead`")  // ⚠️ lead 是 MySQL 保留字，必须反引号包裹
public class LeadContext {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String customerEmail;

    private String companyName;

    private String countryCode;

    /** 当前状态（FSM 状态机驱动） */
    private LeadStatus status;

    /** 决策人 KP */
    private String keyPerson;

    private String keyPersonEmail;

    /** SMTP 验真结果 */
    private Boolean smtpVerified;

    /** DNA 知识库匹配的产品 ID 列表（JSON 字符串） */
    private String matchedProductIds;

    /** 选定的开发信语种 */
    private String selectedLanguage;

    /** 已发送轮次（最多 3 轮） */
    private Integer sentRounds;

    /** 意向分级（阶段五 A/B/C/D 之后） */
    private LeadStatus intentGrade;

    /** 最近一次邮件发送时间 */
    private Instant lastSentAt;

    /** Agent 间共享的临时数据（JSON 字符串） */
    @TableField(value = "extras")
    private String extras;

    private Instant createdAt;

    private Instant updatedAt;

    // ============== 业务辅助方法（不映射到数据库） ==============

    /**
     * 是否已通过 SMTP 验真。
     *
     * <p>⚠️ 不能定义成 {@code isSmtpVerified()}：与 Lombok {@code getSmtpVerified()}
     * 构成 JavaBeans 重载 getter 歧义，MyBatis 反射报
     * {@code Illegal overloaded getter method}。直接用 {@code getSmtpVerified()} 判空即可。
     */
    public boolean smtpVerified() {
        return Boolean.TRUE.equals(smtpVerified);
    }

    /**
     * 是否达到最大追发轮次。
     */
    public boolean isMaxRoundsReached(int maxRounds) {
        return sentRounds != null && sentRounds >= maxRounds;
    }
}