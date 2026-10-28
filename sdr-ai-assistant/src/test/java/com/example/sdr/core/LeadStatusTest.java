package com.example.sdr.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 FSM 状态枚举完整性测试（极简版）。
 *
 * <h2>为什么只测枚举</h2>
 * <p>Spring StateMachine 4.x 的 {@code StateMachineBuilder} API 复杂，
 * 涉及多个 configureXxx 链式调用。教学版的 LeadStateMachineConfig 用
 * {@link StateMachineConfigurerAdapter} 实现，避免了 Builder 的复杂性。
 *
 * <p>本测试聚焦：
 * <ul>
 *   <li>12 个状态枚举都注册</li>
 *   <li>10 个事件枚举都注册</li>
 *   <li>状态分类函数（isActive / isIntentGraded / isParked / isTerminal）正确</li>
 * </ul>
 *
 * <h2>未来扩展</h2>
 * <p>完整 15 个转换规则的 SpringBootTest 集成测试放在 Phase 6 端到端测试时补全。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@DisplayName("LeadStatus / FsmEvent 枚举完整性测试")
class LeadStatusTest {

    // ============================================================
    // LeadStatus 12 个状态枚举覆盖
    // ============================================================

    @Test
    @DisplayName("12 个状态枚举都注册（流程4 + 意向4 + 沉淀3 + 完成1）")
    void testAll12StatusEnumValuesPresent() {
        assertThat(LeadStatus.values()).hasSize(12);
        assertThat(LeadStatus.values()).containsExactlyInAnyOrder(
                LeadStatus.RAW,
                LeadStatus.QUALIFIED,
                LeadStatus.READY,
                LeadStatus.STEP1_SENT,
                LeadStatus.INTENT_A,
                LeadStatus.INTENT_B,
                LeadStatus.INTENT_C,
                LeadStatus.INTENT_D,
                LeadStatus.POOL,
                LeadStatus.BOUNCE,
                LeadStatus.DORMANT,
                LeadStatus.HANDOFF
        );
        log.info("✅ LeadStatus 共 {} 个枚举值", LeadStatus.values().length);
    }

    @Test
    @DisplayName("【分类】4 个流程中状态（isActive=true）")
    void testActiveStates() {
        assertThat(LeadStatus.RAW.isActive()).isTrue();
        assertThat(LeadStatus.QUALIFIED.isActive()).isTrue();
        assertThat(LeadStatus.READY.isActive()).isTrue();
        assertThat(LeadStatus.STEP1_SENT.isActive()).isTrue();
        assertThat(LeadStatus.INTENT_A.isActive()).isFalse();
        log.info("✅ 4 个 active 状态正确");
    }

    @Test
    @DisplayName("【分类】4 个意向分级状态（isIntentGraded=true）")
    void testIntentGradedStates() {
        assertThat(LeadStatus.INTENT_A.isIntentGraded()).isTrue();
        assertThat(LeadStatus.INTENT_B.isIntentGraded()).isTrue();
        assertThat(LeadStatus.INTENT_C.isIntentGraded()).isTrue();
        assertThat(LeadStatus.INTENT_D.isIntentGraded()).isTrue();
        assertThat(LeadStatus.RAW.isIntentGraded()).isFalse();
        log.info("✅ 4 个意向分级状态正确");
    }

    @Test
    @DisplayName("【分类】3 个沉淀状态（isParked=true）")
    void testParkedStates() {
        assertThat(LeadStatus.POOL.isParked()).isTrue();
        assertThat(LeadStatus.BOUNCE.isParked()).isTrue();
        assertThat(LeadStatus.DORMANT.isParked()).isTrue();
        assertThat(LeadStatus.RAW.isParked()).isFalse();
        log.info("✅ 3 个 parked 状态正确");
    }

    @Test
    @DisplayName("【分类】HANDOFF 是终态（isTerminal=true）")
    void testTerminalState() {
        assertThat(LeadStatus.HANDOFF.isTerminal()).isTrue();
        assertThat(LeadStatus.RAW.isTerminal()).isFalse();
        log.info("✅ HANDOFF 终态正确");
    }

    // ============================================================
    // FsmEvent 10 个事件枚举覆盖
    // ============================================================

    @Test
    @DisplayName("10 个事件枚举都注册")
    void testAll10EventEnumValuesPresent() {
        assertThat(FsmEvent.values()).hasSizeGreaterThanOrEqualTo(10);
        // 关键事件必须存在
        assertThat(EnumSet.allOf(FsmEvent.class)).contains(
                FsmEvent.QUALIFY_OK,
                FsmEvent.QUALIFY_FAIL,
                FsmEvent.CONTENT_READY,
                FsmEvent.EMAIL_SENT,
                FsmEvent.EMAIL_REPLIED,
                FsmEvent.EMAIL_BOUNCED,
                FsmEvent.TIMEOUT_72H,
                FsmEvent.MAX_ROUNDS_REACHED,
                FsmEvent.SALES_HANDOVER,
                FsmEvent.RE_CRAWL_TRIGGER
        );
        log.info("✅ FsmEvent 共 {} 个枚举值", FsmEvent.values().length);
    }

    @Test
    @DisplayName("【分类】triggersAgent=true 的事件")
    void testEventsTriggersAgent() {
        // 这些事件触发 Agent 业务处理
        assertThat(FsmEvent.QUALIFY_OK.triggersAgent()).isTrue();
        assertThat(FsmEvent.QUALIFY_FAIL.triggersAgent()).isTrue();
        assertThat(FsmEvent.CONTENT_READY.triggersAgent()).isTrue();
        assertThat(FsmEvent.EMAIL_REPLIED.triggersAgent()).isTrue();
        assertThat(FsmEvent.SALES_HANDOVER.triggersAgent()).isTrue();
        assertThat(FsmEvent.RE_CRAWL_TRIGGER.triggersAgent()).isTrue();
        // 立即事件不触发 Agent
        assertThat(FsmEvent.EMAIL_SENT.triggersAgent()).isFalse();
        assertThat(FsmEvent.EMAIL_BOUNCED.triggersAgent()).isFalse();
        assertThat(FsmEvent.TIMEOUT_72H.triggersAgent()).isFalse();
        assertThat(FsmEvent.MAX_ROUNDS_REACHED.triggersAgent()).isFalse();
        log.info("✅ triggersAgent 分类正确");
    }

    // ============================================================
    // SdrProperties 配置完整性
    // ============================================================

    @Test
    @DisplayName("【SdrProperties】所有可调时间字段都有默认值")
    void testSdrPropertiesDefaults() {
        com.example.sdr.config.SdrProperties props = new com.example.sdr.config.SdrProperties();
        // FSM 时间字段
        assertThat(props.getFsm().getStepWaitTimeoutMs()).isEqualTo(7_200_000L);
        assertThat(props.getFsm().getDormantActivationMs()).isEqualTo(300_000L);
        assertThat(props.getFsm().getMaxFollowupRounds()).isEqualTo(3);
        // Scheduler 频率字段
        assertThat(props.getScheduler().getStep1ScanIntervalMs()).isEqualTo(60_000L);
        assertThat(props.getScheduler().getDormantScanIntervalMs()).isEqualTo(3_600_000L);
        // Agent 字段
        assertThat(props.getAgent().getCallTimeoutMs()).isEqualTo(30_000L);
        // Bandit 字段
        assertThat(props.getBandit().getStrategy()).isEqualTo("thompson-sampling");
        // 便利方法
        assertThat(props.stepWaitTimeout().toMillis()).isEqualTo(7_200_000L);
        log.info("✅ SdrProperties 默认值正确");
    }
}