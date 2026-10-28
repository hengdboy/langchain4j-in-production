package com.example.sdr.core;

/**
 * Ch24 FSM 状态机：销售线索状态枚举（ARCHITECTURE.md §3.1）。
 *
 * <h2>12 状态分类</h2>
 * <ul>
 *   <li><b>流程中（4 个）</b>：RAW → QUALIFIED → READY → STEP1_SENT</li>
 *   <li><b>意向分级（4 个）</b>：INTENT_A / INTENT_B / INTENT_C / INTENT_D</li>
 *   <li><b>沉淀（3 个）</b>：POOL / BOUNCE / DORMANT</li>
 *   <li><b>完成（1 个）</b>：HANDOFF（终态）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
public enum LeadStatus {

    // ============== 流程中状态 ==============
    RAW,
    QUALIFIED,
    READY,
    STEP1_SENT,

    // ============== 阶段五：意向分级 ==============
    INTENT_A,
    INTENT_B,
    INTENT_C,
    INTENT_D,

    // ============== 沉淀状态 ==============
    POOL,
    BOUNCE,
    DORMANT,

    // ============== 完成状态（终态） ==============
    HANDOFF;

    /** 是否处于流程中（可推进的活跃状态） */
    public boolean isActive() {
        return this == RAW || this == QUALIFIED || this == READY || this == STEP1_SENT;
    }

    /** 是否为意向分级状态 */
    public boolean isIntentGraded() {
        return this == INTENT_A || this == INTENT_B || this == INTENT_C || this == INTENT_D;
    }

    /** 是否已沉淀（不再主动触达） */
    public boolean isParked() {
        return this == POOL || this == BOUNCE || this == DORMANT;
    }

    /** 是否为终态（不再转换） */
    public boolean isTerminal() {
        return this == HANDOFF;
    }
}