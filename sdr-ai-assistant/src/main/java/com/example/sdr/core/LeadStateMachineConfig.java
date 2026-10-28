package com.example.sdr.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.util.EnumSet;

/**
 * Ch24 FSM 状态机配置（12 状态 + 10 事件 + 13 转换）。
 *
 * <h2>ARCHITECTURE.md §3 设计契约</h2>
 * <p>本类是 SDR 业务流转的<b>单一权威定义</b>——任何状态转换规则变更必须改这里。
 * 章节 Ch24 §24.4 会讲解每个转换规则的 why。
 *
 * <h2>核心设计</h2>
 * <ul>
 *   <li>用 {@code @EnableStateMachineFactory}（v0.25 修正）—— 注册 {@code StateMachineFactory} Bean，
 *       供 {@link LeadStateMachineService} 按 machineId 取实例；
 *       {@code @EnableStateMachine} 只注册单例机器、<b>不提供 Factory Bean</b>（启动实测报 NoSuchBean）</li>
 *   <li>12 个状态（{@link LeadStatus}）+ 10 个事件（{@link FsmEvent}）</li>
 *   <li>13 条转换规则（含 guard 条件 + action 钩子）</li>
 *   <li>使用 {@link Guard} 守护条件（如 SMTP 验真才能转 QUALIFIED）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Configuration
@EnableStateMachineFactory
public class LeadStateMachineConfig
        extends StateMachineConfigurerAdapter<LeadStatus, FsmEvent> {

    @Override
    public void configure(StateMachineConfigurationConfigurer<LeadStatus, FsmEvent> config)
            throws Exception {
        // 持久化交给 LeadStateMachinePersist（Redis 实现）配置 Bean
        super.configure(config);
    }

    @Override
    public void configure(StateMachineStateConfigurer<LeadStatus, FsmEvent> states)
            throws Exception {
        states.withStates()
                // 初始状态：阶段一新建的 Lead
                .initial(LeadStatus.RAW)
                // 所有合法状态（11 个）
                .states(EnumSet.allOf(LeadStatus.class))
                // 终态：销售接管后不再流转
                .end(LeadStatus.HANDOFF);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<LeadStatus, FsmEvent> tx)
            throws Exception {

        // ============================================================
        // 阶段一 → 阶段二 转换（2 条）
        // ============================================================

        tx.withExternal()
                .source(LeadStatus.RAW).target(LeadStatus.QUALIFIED)
                .event(FsmEvent.QUALIFY_OK)
                .guard(smtpVerifiedGuard())
                .action(ctx -> log.info("✅ [FSM] RAW → QUALIFIED  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(LeadStatus.RAW).target(LeadStatus.POOL)
                .event(FsmEvent.QUALIFY_FAIL)
                .guard(smtpNotVerifiedGuard())
                .action(ctx -> log.info("❌ [FSM] RAW → POOL  leadId={} (SMTP 验真失败)",
                        ctx.getStateMachine().getId()))
                .and();

        // ============================================================
        // 阶段二 → 阶段三 转换（1 条）
        // ============================================================

        tx.withExternal()
                .source(LeadStatus.QUALIFIED).target(LeadStatus.READY)
                .event(FsmEvent.CONTENT_READY)
                .action(ctx -> log.info("✍️ [FSM] QUALIFIED → READY  leadId={}",
                        ctx.getStateMachine().getId()))
                .and();

        // ============================================================
        // 阶段三 → 阶段四 转换（1 条：发送邮件）
        // ============================================================

        tx.withExternal()
                .source(LeadStatus.READY).target(LeadStatus.STEP1_SENT)
                .event(FsmEvent.EMAIL_SENT)
                .guard(maxRoundsNotReachedGuard())
                .action(ctx -> log.info("📧 [FSM] READY → STEP1_SENT  leadId={}",
                        ctx.getStateMachine().getId()))
                .and();

        // ============================================================
        // 阶段四 STEP1_SENT 的 6 条分支（A/B/C/D/Bounce/Dormant）
        // ============================================================

        // 4.1 客户回信 → INTENT_A（高意向）
        tx.withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_A)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("A"))
                .action(ctx -> log.info("🎯 [FSM] STEP1_SENT → INTENT_A  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 4.2 客户回信 → INTENT_B（中意向）
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_B)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("B"))
                .action(ctx -> log.info("📊 [FSM] STEP1_SENT → INTENT_B  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 4.3 客户回信 → INTENT_C（低意向/暂无需求）
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_C)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("C"))
                .action(ctx -> log.info("💤 [FSM] STEP1_SENT → INTENT_C  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 4.4 客户回信 → INTENT_D（明确拒绝）
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_D)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("D"))
                .action(ctx -> log.info("🚫 [FSM] STEP1_SENT → INTENT_D  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 4.5 邮件退回 → BOUNCE
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.BOUNCE)
                .event(FsmEvent.EMAIL_BOUNCED)
                .action(ctx -> log.info("✉️❌ [FSM] STEP1_SENT → BOUNCE  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                // 4.6 达到最大追发轮次 → DORMANT
                // ⚠️ 原 4.6 TIMEOUT_72H 自循环已移除：Spring StateMachine 的 Guard 拦截
                //    无法从 sendEvent 返回值区分（javap 核实），追发判断改由 LeadService.handleTimeout
                //    前置处理（< max 追发重排；≥ max 发 MAX_ROUNDS_REACHED 转 DORMANT）。
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.DORMANT)
                .event(FsmEvent.MAX_ROUNDS_REACHED)
                .action(ctx -> log.info("🛌 [FSM] STEP1_SENT → DORMANT  leadId={}",
                        ctx.getStateMachine().getId()))
                .and();

        // ============================================================
        // 阶段五：意向分级后的 HANDOFF（2 条）
        // ============================================================

        tx.withExternal()
                .source(LeadStatus.INTENT_A).target(LeadStatus.HANDOFF)
                .event(FsmEvent.SALES_HANDOVER)
                .action(ctx -> log.info("🤝 [FSM] INTENT_A → HANDOFF  leadId={}",
                        ctx.getStateMachine().getId()))
                .and()

                .withExternal()
                .source(LeadStatus.INTENT_B).target(LeadStatus.HANDOFF)
                .event(FsmEvent.SALES_HANDOVER)
                .guard(ctx -> ctx.getExtendedState().get("salesAccepted", Boolean.class) == Boolean.TRUE)
                .action(ctx -> log.info("🤝 [FSM] INTENT_B → HANDOFF  leadId={}",
                        ctx.getStateMachine().getId()))
                .and();

        // ============================================================
        // 阶段六：盘活循环（DORMANT → RAW）
        // ============================================================

        tx.withExternal()
                .source(LeadStatus.DORMANT).target(LeadStatus.RAW)
                .event(FsmEvent.RE_CRAWL_TRIGGER)
                .action(ctx -> log.info("♻️ [FSM] DORMANT → RAW  (盘活) leadId={}",
                        ctx.getStateMachine().getId()))
                .and();
    }

    // ============================================================
    // Guard 条件（ARCHITECTURE.md §3.3 表格中的 Guard 列）
    // ============================================================

    /** Guard：SMTP 验真通过 → 才能转 QUALIFIED */
    private Guard<LeadStatus, FsmEvent> smtpVerifiedGuard() {
        return ctx -> Boolean.TRUE.equals(
                ctx.getExtendedState().get("smtpVerified", Boolean.class));
    }

    /** Guard：SMTP 验真失败 → 转 POOL */
    private Guard<LeadStatus, FsmEvent> smtpNotVerifiedGuard() {
        return ctx -> Boolean.FALSE.equals(
                ctx.getExtendedState().get("smtpVerified", Boolean.class));
    }

    /** Guard：sentRounds < maxFollowupRounds → 才能追发 */
    private Guard<LeadStatus, FsmEvent> maxRoundsNotReachedGuard() {
        return ctx -> {
            Integer sentRounds = ctx.getExtendedState().get("sentRounds", Integer.class);
            Integer max = ctx.getExtendedState().get("maxFollowupRounds", Integer.class);
            return sentRounds != null && max != null && sentRounds < max;
        };
    }

    /** Guard：意向分级等于指定字母（A/B/C/D） */
    private Guard<LeadStatus, FsmEvent> intentGradeGuard(String expected) {
        return ctx -> expected.equals(
                ctx.getExtendedState().get("intentGrade", String.class));
    }
}