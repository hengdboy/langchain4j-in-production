package com.example.sdr.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

/**
 * Ch24 FSM 状态变更监听器（StateMachine 内置钩子）。
 *
 * <p><b>@Deprecated（v0.25）</b>：本类从未被 {@link LeadStateMachineConfig} 接线
 * （config 的 action 直接打日志），72h 定时器取消/重排已由
 * {@link com.example.sdr.service.LeadService#handleSideEffects} 在业务层统一处理
 * （EMAIL_REPLIED 取消 / EMAIL_SENT 调度 / MAX_ROUNDS_REACHED 后调度盘活）。
 * 保留本类仅作设计参考，新代码请勿依赖。
 *
 * <h2>原职责（v0.23 设计）</h2>
 * <ul>
 *   <li>状态变更时取消 72h 定时器（避免客户中途回信还触发追发）</li>
 *   <li>写入 lead_event 表（审计流水，v0.25 已由 LeadService.recordEvent 落库）</li>
 *   <li>触发 Ch22 ChatModelListener 埋点（接 LangChain4j 可观测性）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Deprecated
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadStateChangeListener {

    private final DelayedEventQueue delayedQueue;

    /**
     * 状态变更时取消 72h 延迟事件。
     *
     * <p>用法：在 StateMachine Config 的转换规则里：
     * <pre>
     *   .withExternal()
     *     .source(STEP1_SENT).target(INTENT_A)
     *     .event(EMAIL_REPLIED)
     *     .action(cancel72hTimerAction())
     * </pre>
     */
    public Action<LeadStatus, FsmEvent> cancel72hTimerAction() {
        return ctx -> {
            Long leadId = (Long) ctx.getExtendedState().getVariables().get("leadId");
            if (leadId == null) {
                log.warn("⚠️ [StateChangeListener] leadId null in ExtendedState, skip cancel");
                return;
            }
            // 取消 72h 超时（如果存在）
            boolean cancelled = delayedQueue.cancel(leadId, FsmEvent.TIMEOUT_72H);
            log.debug("⏰ [StateChangeListener] leadId={} cancelled72h={}",
                    leadId, cancelled);
        };
    }

    /**
     * 状态变更时重置 72h 定时器（用于追发后开始新一轮倒计时）。
     */
    public Action<LeadStatus, FsmEvent> reset72hTimerAction(java.time.Duration duration) {
        return ctx -> {
            Long leadId = (Long) ctx.getExtendedState().getVariables().get("leadId");
            if (leadId == null) {
                return;
            }
            // 取消旧的 + 调度新的
            delayedQueue.cancel(leadId, FsmEvent.TIMEOUT_72H);
            delayedQueue.schedule(leadId, FsmEvent.TIMEOUT_72H,
                    java.time.Instant.now().plus(duration));
            log.debug("🔄 [StateChangeListener] leadId={} reset72h to {}", leadId, duration);
        };
    }

    /**
     * 记录状态变更到 lead_event 表（审计流水）。
     *
     * <p>教学版简化：直接打印日志。生产版应写 {@code lead_event} 表。
     */
    public Action<LeadStatus, FsmEvent> logTransitionAction(String note) {
        return ctx -> {
            Long leadId = (Long) ctx.getExtendedState().getVariables().get("leadId");
            log.info("📝 [FSM Audit] leadId={} {} → {} (event={})",
                    leadId,
                    ctx.getSource() == null ? "INIT" : ctx.getSource().getId(),
                    ctx.getTarget().getId(),
                    ctx.getEvent() == null ? "INIT" : ctx.getEvent());
        };
    }
}