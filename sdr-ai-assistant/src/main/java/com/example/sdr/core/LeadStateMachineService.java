package com.example.sdr.core;

import com.example.sdr.config.SdrProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultExtendedState;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Ch24 FSM 状态机服务：以 MySQL 为事实源封装 sendEvent / recover / persist。
 *
 * <h2>v0.25 修复的三个集成 bug</h2>
 * <ol>
 *   <li><b>双机器实例</b>：原 {@code LeadService} 把 ExtendedState 写到
 *       {@code createFresh("test-"+id)} 的机器，而事件在 {@code getStateMachine(id)}
 *       上发，Guard 永远读不到值。现在 ExtendedState 统一由本方法在<b>同一机器</b>上构建。</li>
 *   <li><b>无状态恢复</b>：原每次 {@code getStateMachine()} 都从 RAW 初始态开始。
 *       现在每次事件前用 DB 的 {@code lead.status} {@code resetStateMachine}，
 *       保证机器永远从正确状态出发（MySQL 为唯一事实源）。</li>
 *   <li><b>状态写不回 DB</b>：原 {@code readNewStatus} 在 DB 更新前读 MySQL 永远读到旧态。
 *       现在 sendEvent 直接返回新状态，由 {@code LeadService} 用返回值更新 DB。</li>
 * </ol>
 *
 * <h2>核心 API</h2>
 * <pre>
 *   sendEvent(LeadContext, FsmEvent, Map) → LeadStatus
 *     - 成功转换：返回新状态（LeadService 落库 + 副作用）
 *     - Guard 拒绝 / 状态不匹配：返回 null（LeadService 记录并原样返回）
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadStateMachineService {

    private final StateMachineFactory<LeadStatus, FsmEvent> stateMachineFactory;
    private final LeadStateMachinePersist persist;
    private final SdrProperties props;

    /**
     * 给指定 lead 发送事件。
     *
     * @param lead      当前 lead（MySQL 事实源：status / sentRounds / intentGrade / smtpVerified）
     * @param event     要发送的事件
     * @param overrides 额外写入 ExtendedState 的键值（如 salesAccepted），可为空
     * @return 转换成功返回新状态；Guard 拒绝或状态不匹配返回 {@code null}
     */
    public LeadStatus sendEvent(LeadContext lead, FsmEvent event, Map<String, Object> overrides) {
        Long leadId = lead.getId();

        // 1. 从工厂按 machineId 获取 StateMachine 实例
        StateMachine<LeadStatus, FsmEvent> sm =
                stateMachineFactory.getStateMachine(leadId.toString());

        // 2. 构建 ExtendedState（同一机器，来自 lead 字段 + overrides）
        // ⚠️ 不能有 null 值：resetStateMachine 会把 extended 拷进 ConcurrentHashMap，null 值抛 NPE
        Map<String, Object> extended = buildExtendedState(lead);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null) extended.put(k, v);
            });
        }

        // 3. 以 DB 状态为事实源重置（修复 bug 2：不再从 RAW 初始态开始）
        DefaultStateMachineContext<LeadStatus, FsmEvent> ctx =
                new DefaultStateMachineContext<>(lead.getStatus(), null, new HashMap<>(),
                        new DefaultExtendedState(new HashMap<>(extended)));
        sm.getStateMachineAccessor().doWithAllRegions(access -> access.resetStateMachine(ctx));

        // 4. 启动（幂等）+ 把业务上下文写入同一机器的 ExtendedState
        sm.startReactively().block();
        sm.getExtendedState().getVariables().put("leadId", leadId);
        sm.getExtendedState().getVariables().putAll(extended);

        // 5. 发送事件（同步）
        LeadStatus before = sm.getState().getId();
        sm.sendEvent(MessageBuilder.withPayload(event).build());
        LeadStatus after = sm.getState().getId();

        // 6. 判定是否真正发生状态迁移
        // ⚠️ Spring StateMachine 的 sendEvent 布尔返回值在 Guard 拒绝时仍返回 true
        //    （guard-blocked 的 ResultType 是 ACCEPTED，javap 核实），无法区分"转换已执行"与"Guard 拦截"。
        //    唯一可靠信号是状态前后是否变化；自循环（TIMEOUT_72H）已移出 FSM，由 LeadService 前置判断处理。
        if (before == after) {
            log.warn("⚠️ [FSM Service] leadId={} event={} → 未发生状态迁移 (state: {}，Guard 拒绝或无匹配事件)",
                    leadId, event, lead.getStatus());
            return null;
        }

        LeadStatus newStatus = after;

        // 7. 写透 Redis 快照（非事实源，审计/观测用）
        persist.save(newStatus, extended, leadId);

        log.info("✅ [FSM Service] leadId={} event={} → {} (state: {})",
                leadId, event, newStatus, before);
        return newStatus;
    }

    /**
     * 启动一个全新的 StateMachine 实例（不重置，直接初始态）。
     * 仅用于教学测试 / 单元测试。
     */
    public StateMachine<LeadStatus, FsmEvent> createFresh(Long leadId) {
        StateMachine<LeadStatus, FsmEvent> sm =
                stateMachineFactory.getStateMachine("test-" + leadId);
        sm.startReactively().block();
        return sm;
    }

    /**
     * 从 Redis 快照恢复指定 lead 的 StateMachine 当前状态（观测用，非运行依赖）。
     */
    public LeadStatus restoreState(Long leadId) {
        var ctx = persist.load(leadId);
        if (ctx == null) {
            return LeadStatus.RAW;
        }
        return ctx.getState();
    }

    /** 构建状态机 Guard 依赖的 ExtendedState（来自 lead 业务字段 + props） */
    private Map<String, Object> buildExtendedState(LeadContext lead) {
        Map<String, Object> map = new HashMap<>();
        map.put("leadId", lead.getId());
        map.put("smtpVerified", lead.smtpVerified());
        map.put("sentRounds", lead.getSentRounds() != null ? lead.getSentRounds() : 0);
        map.put("maxFollowupRounds", props.getFsm().getMaxFollowupRounds());
        // Guard intentGradeGuard("A") 比较的是字母（"A"/"B"/"C"/"D"），不是枚举名 "INTENT_A"；
        // 无 intentGrade 时跳过（缺键 → Guard 返回 false，符合"无分级不可路由"语义）
        if (lead.getIntentGrade() != null) {
            map.put("intentGrade", intentGradeLetter(lead.getIntentGrade()));
        }
        return map;
    }

    /** INTENT_A → "A"，null → null */
    private String intentGradeLetter(LeadStatus s) {
        if (s == null) return null;
        return s.name().substring("INTENT_".length());
    }
}
