package com.example.sdr.service;

import com.example.sdr.config.SdrProperties;
import com.example.sdr.core.DelayedEventQueue;
import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadEvent;
import com.example.sdr.core.LeadStateMachineService;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.core.LeadTransitionedEvent;
import com.example.sdr.mapper.LeadEventMapper;
import com.example.sdr.mapper.LeadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Ch24 SDR 业务层：Lead 状态变更的统一入口。
 *
 * <h2>v0.25 修复的集成 bug</h2>
 * <ol>
 *   <li>不再往 {@code createFresh("test-"+id)} 的机器写 ExtendedState（双机器 bug，
 *       由 {@link LeadStateMachineService} 统一在事件机上构建）。</li>
 *   <li>不再用 {@code readNewStatus} 从 MySQL 读旧状态（该读发生在 DB 更新前，永远读到旧态）。
 *       直接用 {@code sendEvent} 返回值更新 DB。</li>
 *   <li>补上 {@code TIMEOUT_72H} 被 Guard 拒绝（达最大轮次）后的
 *       {@code MAX_ROUNDS_REACHED} 触发链 → DORMANT。</li>
 * </ol>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>封装状态变更的所有副作用（DB 更新、延迟队列调度、审计落库）</li>
 *   <li>让 Agent / Controller 只调 {@link #processEvent}，不直接接触 StateMachine</li>
 *   <li>统一从 MySQL 读 Lead → 用 StateMachine 处理 → 写回 MySQL + lead_event</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadMapper leadMapper;
    private final LeadEventMapper leadEventMapper;
    private final LeadStateMachineService fsmService;
    private final DelayedEventQueue delayedQueue;
    private final SdrProperties props;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 处理业务事件（无额外上下文，供 DelayedEventWorker / 简单场景使用）。
     */
    public LeadContext processEvent(Long leadId, FsmEvent event) {
        return processEvent(leadId, event, Map.of());
    }

    /**
     * 处理业务事件：读 Lead → 状态机处理 → 写回 Lead → 调度/取消延迟事件 → 审计落库。
     *
     * @param leadId    销售线索 ID
     * @param event     要处理的事件
     * @param overrides 额外写入 ExtendedState 的键值（如 intentGrade="A"、salesAccepted=true）
     * @return 更新后的 LeadContext（Guard 拒绝则原样返回）
     */
    public LeadContext processEvent(Long leadId, FsmEvent event, Map<String, Object> overrides) {
        log.info("📨 [LeadService] processing leadId={} event={} overrides={}", leadId, event, overrides);

        // 静默期超时：业务前置判断（追发 / 转 DORMANT），不走 FSM 自循环
        if (event == FsmEvent.TIMEOUT_72H) {
            return handleTimeout(leadId);
        }

        // 1. 读 Lead（MySQL）
        LeadContext lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw new IllegalArgumentException("Lead not found: " + leadId);
        }
        LeadStatus oldStatus = lead.getStatus();

        // 2. 应用覆盖字段（intentGrade / smtpVerified 等持久化字段）
        applyOverrides(lead, overrides);

        // 3. 状态机处理（同机 ExtendedState + DB 状态重置，返回新状态或 null）
        LeadStatus newStatus = fsmService.sendEvent(lead, event, overrides);

        // 4. 拒绝则原样返回（不改库）
        if (newStatus == null) {
            log.warn("⚠️ [LeadService] event {} rejected by StateMachine, lead unchanged", event);
            return lead;
        }

        // 5. 更新状态 + 副作用（轮次/延迟事件/审计）
        lead.setStatus(newStatus);
        handleSideEffects(event, lead, oldStatus, newStatus);
        leadMapper.updateById(lead);
        recordEvent(lead, event, oldStatus, newStatus);

        // 6. 发布状态迁移事件（事件驱动编排：LeadPipelineService 监听并触发下一个 Agent）
        eventPublisher.publishEvent(new LeadTransitionedEvent(leadId, oldStatus, newStatus, event));

        log.info("✅ [LeadService] leadId={} {} → {}", leadId, oldStatus, newStatus);
        return lead;
    }

    /**
     * 处理静默期超时（TIMEOUT_72H，由 DelayedEventWorker 触发）。
     *
     * <p><b>为什么不在 FSM 里做自循环</b>：Spring StateMachine 的 Guard 拦截无法从
     * {@code sendEvent} 返回值区分（javap 核实），自循环状态不变，只能靠业务层前置判断。
     * 这里读 DB 决定追发或沉睡：
     * <ul>
     *   <li>sentRounds &lt; max → 追发下一轮（sentRounds+1，重排 72h 监听）</li>
     *   <li>sentRounds ≥ max → 状态机发 MAX_ROUNDS_REACHED → DORMANT（并调度 90 天盘活）</li>
     * </ul>
     *
     * @return 更新后的 LeadContext（若已不在 STEP1_SENT 则原样返回，忽略过期超时）
     */
    public LeadContext handleTimeout(Long leadId) {
        LeadContext lead = leadMapper.selectById(leadId);
        if (lead == null || lead.getStatus() != LeadStatus.STEP1_SENT) {
            log.info("⏰ [LeadService] leadId={} timeout ignored (status={})", leadId, lead == null ? null : lead.getStatus());
            return lead;
        }

        int rounds = lead.getSentRounds() == null ? 0 : lead.getSentRounds();
        if (rounds < props.getFsm().getMaxFollowupRounds()) {
            // 追发下一轮
            lead.setSentRounds(rounds + 1);
            lead.setLastSentAt(Instant.now());
            leadMapper.updateById(lead);
            schedule(leadId, FsmEvent.TIMEOUT_72H, props.stepWaitTimeout());
            recordEvent(lead, FsmEvent.TIMEOUT_72H, LeadStatus.STEP1_SENT, LeadStatus.STEP1_SENT);
            log.info("🔄 [LeadService] leadId={} follow-up round {}, next TIMEOUT_72H scheduled", leadId, rounds + 1);
            return lead;
        }

        // 已达最大轮次 → 状态机转 DORMANT
        log.info("🛌 [LeadService] leadId={} max follow-up rounds reached, moving to DORMANT", leadId);
        return processEvent(leadId, FsmEvent.MAX_ROUNDS_REACHED, Map.of());
    }

    // ===================== 私有辅助 =====================

    /** 把 overrides 里能映射到 lead 持久化字段的值写回 lead（其余键只进 ExtendedState） */
    private void applyOverrides(LeadContext lead, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        if (overrides.containsKey("smtpVerified") && overrides.get("smtpVerified") instanceof Boolean b) {
            lead.setSmtpVerified(b);
        }
        if (overrides.containsKey("intentGrade") && overrides.get("intentGrade") != null) {
            lead.setIntentGrade(parseIntentGrade(String.valueOf(overrides.get("intentGrade"))));
        }
    }

    private LeadStatus parseIntentGrade(String grade) {
        String g = grade.trim().toUpperCase();
        return switch (g) {
            case "A", "INTENT_A" -> LeadStatus.INTENT_A;
            case "B", "INTENT_B" -> LeadStatus.INTENT_B;
            case "C", "INTENT_C" -> LeadStatus.INTENT_C;
            case "D", "INTENT_D" -> LeadStatus.INTENT_D;
            default -> throw new IllegalArgumentException("invalid intentGrade: " + grade);
        };
    }

    /** 状态变更的延迟事件副作用：发送后调度 72h、回信取消、达上限转沉睡并调度盘活 */
    private void handleSideEffects(FsmEvent event, LeadContext lead,
                                   LeadStatus oldStatus, LeadStatus newStatus) {
        Long leadId = lead.getId();

        switch (event) {
            case EMAIL_SENT -> {
                if (newStatus == LeadStatus.STEP1_SENT) {
                    lead.setSentRounds(1);
                    lead.setLastSentAt(Instant.now());
                    schedule(leadId, FsmEvent.TIMEOUT_72H, props.stepWaitTimeout());
                }
            }
            case MAX_ROUNDS_REACHED -> {
                if (newStatus == LeadStatus.DORMANT) {
                    schedule(leadId, FsmEvent.RE_CRAWL_TRIGGER, props.dormantActivation());
                }
            }
            case EMAIL_REPLIED -> {
                if (oldStatus == LeadStatus.STEP1_SENT) {
                    delayedQueue.cancel(leadId, FsmEvent.TIMEOUT_72H);
                    log.info("🗑 [LeadService] cancelled TIMEOUT_72H for leadId={}", leadId);
                }
            }
            default -> { }
        }
    }

    private void schedule(Long leadId, FsmEvent event, Duration delay) {
        Instant fireAt = Instant.now().plus(delay);
        delayedQueue.schedule(leadId, event, fireAt);
        log.info("📥 [LeadService] scheduled {} for leadId={} at {}", event, leadId, fireAt);
    }

    /** 审计：每次 accepted 转换写入 lead_event 表 */
    private void recordEvent(LeadContext lead, FsmEvent event,
                             LeadStatus oldStatus, LeadStatus newStatus) {
        LeadEvent ev = new LeadEvent();
        ev.setLeadId(lead.getId());
        ev.setFromStatus(oldStatus == null ? null : oldStatus.name());
        ev.setToStatus(newStatus.name());
        ev.setEvent(event.name());
        ev.setActor("system");
        ev.setMetadata(lead.getExtras());
        ev.setCreatedAt(Instant.now());
        leadEventMapper.insert(ev);
    }
}
