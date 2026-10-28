package com.example.sdr.core;

/**
 * Ch24 FSM 状态机：状态转换事件枚举（ARCHITECTURE.md §3.2）。
 *
 * <p>10 个事件，每个事件对应一个或多个状态转换规则。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
public enum FsmEvent {

    /** 背调通过（阶段二结果） */
    QUALIFY_OK,

    /** 背调失败（无海关/无KP/邮箱无效） */
    QUALIFY_FAIL,

    /** 内容就绪（开发信生成完毕） */
    CONTENT_READY,

    /** 邮件已发送（任何轮次） */
    EMAIL_SENT,

    /** 客户回信（阶段五入口） */
    EMAIL_REPLIED,

    /** 邮件退回（邮箱错误/被拉黑） */
    EMAIL_BOUNCED,

    /** 72 小时静默期超时 */
    TIMEOUT_72H,

    /** 3 轮追发完毕仍无回复 */
    MAX_ROUNDS_REACHED,

    /** 销售接管（A/B 级触发） */
    SALES_HANDOVER,

    /** 90 天盘活（DORMANT 状态定时触发） */
    RE_CRAWL_TRIGGER;

    /** 是否会触发 Agent 业务逻辑 */
    public boolean triggersAgent() {
        return this == QUALIFY_OK || this == QUALIFY_FAIL
                || this == CONTENT_READY || this == EMAIL_REPLIED
                || this == SALES_HANDOVER || this == RE_CRAWL_TRIGGER;
    }
}