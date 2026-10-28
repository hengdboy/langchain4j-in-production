package com.example.hiring.core;

import com.example.hiring.config.HiringProperties;
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
 * Ch24 FSM 状态机服务：以 MySQL 为事实源封装 sendEvent / persist。
 *
 * <h2>三个集成 bug（SDR 版 v0.25 踩坑实录，招聘版同样适用）</h2>
 * <ol>
 *   <li><b>双机器实例</b>：把 ExtendedState 写到 A 机器、事件发到 B 机器，Guard 永远读不到值。
 *       ExtendedState 必须在<b>同一台</b>事件机上构建。</li>
 *   <li><b>无状态恢复</b>：每次 {@code getStateMachine()} 都从初始态开始。每次事件前用
 *       DB 的 {@code candidate.status} {@code resetStateMachine}，MySQL 为唯一事实源。</li>
 *   <li><b>状态写不回 DB</b>：从 DB 读新状态发生在 DB 更新前，永远读到旧态。
 *       sendEvent 直接返回新状态，由调用方落库。</li>
 * </ol>
 *
 * <h2>核心 API</h2>
 * <pre>
 *   sendEvent(CandidateContext, FsmEvent, Map) → CandidateStatus
 *     - 成功转换：返回新状态（CandidateService 落库 + 副作用）
 *     - Guard 拒绝 / 状态不匹配：返回 null（调用方记录并原样返回）
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateStateMachineService {

    private final StateMachineFactory<CandidateStatus, FsmEvent> stateMachineFactory;
    private final CandidateStateMachinePersist persist;
    private final HiringProperties props;

    /**
     * 给指定候选人发送事件。
     *
     * @param candidate 当前候选人（MySQL 事实源：status / inviteRounds / matchVerdict）
     * @param event     要发送的事件
     * @param overrides 额外写入 ExtendedState 的键值（verdict / replyDecision / evalOutcome /
     *                  offerDecision / humanApproved），可为空
     * @return 转换成功返回新状态；Guard 拒绝或状态不匹配返回 {@code null}
     */
    public CandidateStatus sendEvent(CandidateContext candidate, FsmEvent event,
                                     Map<String, Object> overrides) {
        Long candidateId = candidate.getId();

        // 1. 从工厂按 machineId 获取 StateMachine 实例
        StateMachine<CandidateStatus, FsmEvent> sm =
                stateMachineFactory.getStateMachine(candidateId.toString());

        // 2. 构建 ExtendedState（同一机器；来自 candidate 字段 + overrides）
        // ⚠️ 不能有 null 值：resetStateMachine 拷进 ConcurrentHashMap 时 null 值抛 NPE
        Map<String, Object> extended = buildExtendedState(candidate);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null) extended.put(k, v);
            });
        }

        // 3. 以 DB 状态为事实源重置（修复 bug 2）
        DefaultStateMachineContext<CandidateStatus, FsmEvent> ctx =
                new DefaultStateMachineContext<>(candidate.getStatus(), null, new HashMap<>(),
                        new DefaultExtendedState(new HashMap<>(extended)));
        sm.getStateMachineAccessor().doWithAllRegions(access -> access.resetStateMachine(ctx));

        // 4. 启动（幂等）+ 把业务上下文写入同一机器的 ExtendedState
        sm.startReactively().block();
        sm.getExtendedState().getVariables().put("candidateId", candidateId);
        sm.getExtendedState().getVariables().putAll(extended);

        // 5. 发送事件（同步）
        CandidateStatus before = sm.getState().getId();
        sm.sendEvent(MessageBuilder.withPayload(event).build());
        CandidateStatus after = sm.getState().getId();

        // 6. 判定是否真正发生状态迁移
        // ⚠️ Spring StateMachine 的 sendEvent 布尔返回值在 Guard 拒绝时仍返回 true，
        //    唯一可靠信号是状态前后是否变化；超时类自循环已移出 FSM。
        if (before == after) {
            log.warn("⚠️ [FSM Service] candidateId={} event={} → 未发生状态迁移 (state: {}，Guard 拒绝或无匹配事件)",
                    candidateId, event, candidate.getStatus());
            return null;
        }

        CandidateStatus newStatus = after;

        // 7. 写透 Redis 快照（非事实源，审计/观测用）
        persist.save(newStatus, extended, candidateId);

        log.info("✅ [FSM Service] candidateId={} event={} → {} (state: {})",
                candidateId, event, newStatus, before);
        return newStatus;
    }

    /** 从 Redis 快照恢复指定候选人的 StateMachine 当前状态（观测用，非运行依赖）。 */
    public CandidateStatus restoreState(Long candidateId) {
        var ctx = persist.load(candidateId);
        if (ctx == null) {
            return CandidateStatus.APPLIED;
        }
        return ctx.getState();
    }

    /** 构建状态机 Guard 依赖的 ExtendedState（来自 candidate 业务字段） */
    private Map<String, Object> buildExtendedState(CandidateContext candidate) {
        Map<String, Object> map = new HashMap<>();
        map.put("candidateId", candidate.getId());
        // verdict 持久化在 candidate.matchVerdict；ON_HOLD 复核场景 Guard 需要它兜底
        if (candidate.getMatchVerdict() != null) {
            map.put("verdict", candidate.getMatchVerdict());
        }
        return map;
    }
}
