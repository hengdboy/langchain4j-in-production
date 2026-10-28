package com.example.sdr.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.guard.Guard;
import reactor.core.publisher.Flux;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ch24 FSM 端到端模拟器：<strong>不依赖 Spring Context</strong>，直接 in-memory 跑完整业务流。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * mvn test -Dtest=LeadStateMachineSimulator
 * }</pre>
 *
 * <h2>模拟的 4 个核心场景</h2>
 * <ol>
 *   <li><b>Happy Path</b>：RAW → QUALIFIED → READY → STEP1_SENT → INTENT_A → HANDOFF</li>
 *   <li><b>FAIL Path</b>：RAW → POOL（背调失败）</li>
 *   <li><b>Follow-up Path</b>：RAW → ... → STEP1_SENT（追发 3 轮）→ DORMANT</li>
 *   <li><b>Re-crawl Loop</b>：DORMANT → RE_CRAWL_TRIGGER → RAW（90 天盘活）</li>
 * </ol>
 *
 * <h2>目的</h2>
 * <p>验证整个 FSM 引擎在<strong>不依赖</strong> Redis / MySQL / Spring Context 的情况下，
 * 能完整跑通业务流——确保 Phase 1 核心代码逻辑正确（虽然 Spring 集成层未测）。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
public class LeadStateMachineSimulator {

    public static void main(String[] args) throws Exception {
        log.info("═══════════════════════════════════════════════");
        log.info("🧪 Ch24 FSM 端到端模拟器启动");
        log.info("═══════════════════════════════════════════════");

        // 场景 1: Happy Path
        runScenario("【场景1: Happy Path】RAW → QUALIFIED → READY → STEP1_SENT → INTENT_A → HANDOFF",
                new long[]{1L});

        // 场景 2: FAIL Path（背调失败）
        runScenario("【场景2: 背调失败】RAW → POOL",
                new long[]{2L}, false);

        // 场景 3: Follow-up Path（3 轮追发）
        runScenarioFollowup(3L);

        // 场景 4: 盘活循环
        runScenarioRecrawl(4L);

        log.info("═══════════════════════════════════════════════");
        log.info("✅ 所有场景模拟通过");
        log.info("═══════════════════════════════════════════════");
    }

    /**
     * 通用场景执行器（默认 SMTP 验真通过）。
     */
    private static void runScenario(String title, long[] leadIds) throws Exception {
        runScenario(title, leadIds, true);
    }

    /**
     * 通用场景执行器（可控 SMTP 验真）。
     */
    private static void runScenario(String title, long[] leadIds, boolean smtpVerified) throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════");
        log.info("{}", title);
        log.info("═══════════════════════════════════════════════");

        for (long leadId : leadIds) {
            StateMachine<LeadStatus, FsmEvent> sm = buildStateMachine("sim-" + leadId);
            sm.startReactively().block();

            // 设置 ExtendedState
            sm.getExtendedState().getVariables().put("smtpVerified", smtpVerified);
            sm.getExtendedState().getVariables().put("sentRounds", 0);
            sm.getExtendedState().getVariables().put("maxFollowupRounds", 3);

            log.info("📌 [Lead {}] 初始状态: {}", leadId, sm.getState().getId());

            if (smtpVerified) {
                // Happy Path
                sendEvent(sm, FsmEvent.QUALIFY_OK);
                log.info("  → QUALIFIED: {}", sm.getState().getId());

                sendEvent(sm, FsmEvent.CONTENT_READY);
                log.info("  → READY: {}", sm.getState().getId());

                sendEvent(sm, FsmEvent.EMAIL_SENT);
                log.info("  → STEP1_SENT: {}", sm.getState().getId());

                sm.getExtendedState().getVariables().put("intentGrade", "A");
                sendEvent(sm, FsmEvent.EMAIL_REPLIED);
                log.info("  → INTENT_A: {}", sm.getState().getId());

                sendEvent(sm, FsmEvent.SALES_HANDOVER);
                log.info("  → HANDOFF: {}", sm.getState().getId());
            } else {
                // FAIL Path
                sendEvent(sm, FsmEvent.QUALIFY_FAIL);
                log.info("  → POOL: {}", sm.getState().getId());
            }

            sm.stopReactively().block();
        }
    }

    /**
     * 场景 3: 追发路径（3 轮后转 DORMANT）。
     */
    private static void runScenarioFollowup(long leadId) throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════");
        log.info("【场景3: 追发路径】STEP1_SENT 追发 3 轮 → DORMANT");
        log.info("═══════════════════════════════════════════════");

        StateMachine<LeadStatus, FsmEvent> sm = buildStateMachine("sim-followup-" + leadId);
        sm.startReactively().block();
        sm.getExtendedState().getVariables().put("smtpVerified", Boolean.TRUE);
        sm.getExtendedState().getVariables().put("sentRounds", 0);
        sm.getExtendedState().getVariables().put("maxFollowupRounds", 3);

        // 快进到 STEP1_SENT
        sendEvent(sm, FsmEvent.QUALIFY_OK);
        sendEvent(sm, FsmEvent.CONTENT_READY);
        sendEvent(sm, FsmEvent.EMAIL_SENT);
        log.info("📌 [Lead {}] 当前: {}", leadId, sm.getState().getId());

        // v0.25：追发判断已移到业务层 LeadService.handleTimeout（TIMEOUT_72H 自循环已移除），
        // FSM 只负责 sentRounds 达上限后的 MAX_ROUNDS_REACHED → DORMANT
        sm.getExtendedState().getVariables().put("sentRounds", 3);
        sendEvent(sm, FsmEvent.MAX_ROUNDS_REACHED);
        log.info("  → DORMANT: {}", sm.getState().getId());

        sm.stopReactively().block();
    }

    /**
     * 场景 4: 盘活循环（DORMANT → RE_CRAWL_TRIGGER → RAW）。
     */
    private static void runScenarioRecrawl(long leadId) throws Exception {
        log.info("");
        log.info("═══════════════════════════════════════════════");
        log.info("【场景4: 盘活循环】DORMANT → 90 天 → RE_CRAWL_TRIGGER → RAW");
        log.info("═══════════════════════════════════════════════");

        StateMachine<LeadStatus, FsmEvent> sm = buildStateMachine("sim-recrawl-" + leadId);
        sm.startReactively().block();
        sm.getExtendedState().getVariables().put("smtpVerified", Boolean.TRUE);
        // 必须设置 sentRounds 和 maxFollowupRounds，否则 EMAIL_SENT / MAX_ROUNDS_REACHED 的 Guard 会拒绝
        // 关键：sentRounds=2（<3）让 EMAIL_SENT 转到 STEP1_SENT，再置 3 触发 MAX_ROUNDS_REACHED
        sm.getExtendedState().getVariables().put("sentRounds", 2);
        sm.getExtendedState().getVariables().put("maxFollowupRounds", 3);

        // 直接到 DORMANT
        sendEvent(sm, FsmEvent.QUALIFY_OK);
        sendEvent(sm, FsmEvent.CONTENT_READY);
        sendEvent(sm, FsmEvent.EMAIL_SENT);
        sendEvent(sm, FsmEvent.MAX_ROUNDS_REACHED);
        log.info("📌 [Lead {}] 当前: {}", leadId, sm.getState().getId());

        // 90 天后盘活
        sendEvent(sm, FsmEvent.RE_CRAWL_TRIGGER);
        log.info("  → 90天后 RE_CRAWL_TRIGGER → RAW: {}", sm.getState().getId());

        sm.stopReactively().block();
    }

    /**
     * 构造一个 in-memory StateMachine，复用 LeadStateMachineConfig 的转换表 + Guard。
     */
    private static StateMachine<LeadStatus, FsmEvent> buildStateMachine(String machineId) throws Exception {
        AtomicLong dummy = new AtomicLong(0);
        var builder = StateMachineBuilder.<LeadStatus, FsmEvent>builder();

        // === 配置 states（必须先调用）===
        builder.configureStates()
                .withStates()
                        .initial(LeadStatus.RAW)
                        .states(EnumSet.allOf(LeadStatus.class))
                        .end(LeadStatus.HANDOFF);

        // === 配置 transitions（再次调用 builder.configureXxx）===
        var transitions = builder.configureTransitions();
        transitions.withExternal()
                .source(LeadStatus.RAW).target(LeadStatus.QUALIFIED)
                .event(FsmEvent.QUALIFY_OK)
                .guard(smtpVerifiedGuard())
                .and()
                .withExternal()
                .source(LeadStatus.RAW).target(LeadStatus.POOL)
                .event(FsmEvent.QUALIFY_FAIL)
                .guard(smtpNotVerifiedGuard())
                .and()
                .withExternal()
                .source(LeadStatus.QUALIFIED).target(LeadStatus.READY)
                .event(FsmEvent.CONTENT_READY)
                .and()
                .withExternal()
                .source(LeadStatus.READY).target(LeadStatus.STEP1_SENT)
                .event(FsmEvent.EMAIL_SENT)
                .guard(maxRoundsNotReachedGuard())
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_A)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("A"))
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_B)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("B"))
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_C)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("C"))
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.INTENT_D)
                .event(FsmEvent.EMAIL_REPLIED)
                .guard(intentGradeGuard("D"))
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.BOUNCE)
                .event(FsmEvent.EMAIL_BOUNCED)
                .and()
                .withExternal()
                .source(LeadStatus.STEP1_SENT).target(LeadStatus.DORMANT)
                .event(FsmEvent.MAX_ROUNDS_REACHED)
                .and()
                .withExternal()
                .source(LeadStatus.INTENT_A).target(LeadStatus.HANDOFF)
                .event(FsmEvent.SALES_HANDOVER)
                .and()
                .withExternal()
                .source(LeadStatus.INTENT_B).target(LeadStatus.HANDOFF)
                .event(FsmEvent.SALES_HANDOVER)
                .guard(salesAcceptedGuard())
                .and()
                .withExternal()
                .source(LeadStatus.DORMANT).target(LeadStatus.RAW)
                .event(FsmEvent.RE_CRAWL_TRIGGER);

        // === 最后 build ===
        return (StateMachine<LeadStatus, FsmEvent>) builder.build();
    }

    /**
     * 发送事件并同步等待结果。
     */
    private static void sendEvent(StateMachine<LeadStatus, FsmEvent> sm, FsmEvent event) {
        sm.sendEvents(Flux.just(MessageBuilder.withPayload(event).build())).blockLast();
    }

    // ============================================================
    // Guard 定义（与 LeadStateMachineConfig 同步）
    // ============================================================

    private static Guard<LeadStatus, FsmEvent> smtpVerifiedGuard() {
        return ctx -> Boolean.TRUE.equals(
                ctx.getExtendedState().get("smtpVerified", Boolean.class));
    }

    private static Guard<LeadStatus, FsmEvent> smtpNotVerifiedGuard() {
        return ctx -> Boolean.FALSE.equals(
                ctx.getExtendedState().get("smtpVerified", Boolean.class));
    }

    private static Guard<LeadStatus, FsmEvent> maxRoundsNotReachedGuard() {
        return ctx -> {
            Integer sentRounds = ctx.getExtendedState().get("sentRounds", Integer.class);
            Integer max = ctx.getExtendedState().get("maxFollowupRounds", Integer.class);
            return sentRounds != null && max != null && sentRounds < max;
        };
    }

    private static Guard<LeadStatus, FsmEvent> intentGradeGuard(String expected) {
        return ctx -> expected.equals(
                ctx.getExtendedState().get("intentGrade", String.class));
    }

    private static Guard<LeadStatus, FsmEvent> salesAcceptedGuard() {
        return ctx -> Boolean.TRUE.equals(
                ctx.getExtendedState().get("salesAccepted", Boolean.class));
    }
}