package com.example.hiring.core;

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
 * FSM 状态机服务：以 MySQL 为事实源封装 sendEvent / persist。
 *
 * <p>三个集成 bug 的教训（上一版项目实测，本项目同样适用）：
 * ExtendedState 必须与事件在同一台状态机实例上构建，写到 A 台、发到 B 台，
 * Guard 永远读不到值；每次事件前用 DB 的 candidate.status resetStateMachine，
 * MySQL 是唯一事实源，否则机器从初始态出发；新状态以 sendEvent 返回值为准、
 * 由调用方落库——事后从 DB 回读发生在更新之前，永远读到旧值。
 *
 * <p>sendEvent 转换成功返回新状态；Guard 拒绝或状态不匹配返回 null，
 * 由调用方记录并原样返回。
 * @author hengdboy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateStateMachineService {

    private final StateMachineFactory<CandidateStatus, FsmEvent> stateMachineFactory;
    private final CandidateStateMachinePersist persist;

    /**
     * 给指定候选人发送事件。
     *
     * @param candidate 当前候选人（MySQL 事实源：status / inviteRounds / matchVerdict）
     * @param event     要发送的事件
     * @param overrides 额外写入 ExtendedState 的键值（verdict / replyDecision / evalOutcome /
     * offerDecision / humanApproved），可为空
     * @return 转换成功返回新状态；Guard 拒绝或状态不匹配返回 {@code null}
     */
    public CandidateStatus sendEvent(CandidateContext candidate, FsmEvent event,
                                     Map<String, Object> overrides) {
        Long candidateId = candidate.getId();

        // 从工厂按 machineId 获取 StateMachine 实例
        StateMachine<CandidateStatus, FsmEvent> sm =
                stateMachineFactory.getStateMachine(candidateId.toString());

        // 构建 ExtendedState（同一机器；来自 candidate 字段 + overrides）
        // 不能有 null 值：resetStateMachine 拷进 ConcurrentHashMap 时 null 值抛 NPE
        Map<String, Object> extended = buildExtendedState(candidate);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null) extended.put(k, v);
            });
        }

        // 以 DB 状态为事实源重置
        DefaultStateMachineContext<CandidateStatus, FsmEvent> ctx =
                new DefaultStateMachineContext<>(candidate.getStatus(), null, new HashMap<>(),
                        new DefaultExtendedState(new HashMap<>(extended)));
        sm.getStateMachineAccessor().doWithAllRegions(access -> access.resetStateMachine(ctx));

        // 启动（幂等）+ 把业务上下文写入同一机器的 ExtendedState
        sm.startReactively().block();
        sm.getExtendedState().getVariables().put("candidateId", candidateId);
        sm.getExtendedState().getVariables().putAll(extended);

        // 发送事件（同步）
        CandidateStatus before = sm.getState().getId();
        sm.sendEvent(MessageBuilder.withPayload(event).build());
        CandidateStatus after = sm.getState().getId();

        // 判定是否真正发生状态迁移
        // Spring StateMachine 的 sendEvent 布尔返回值在 Guard 拒绝时仍返回 true，
        // 唯一可靠信号是状态前后是否变化；超时类自循环已移出 FSM。
        if (before == after) {
            log.warn("[FSM Service] candidateId={} event={} → 未发生状态迁移 (state: {}，Guard 拒绝或无匹配事件)",
                    candidateId, event, candidate.getStatus());
            return null;
        }

        CandidateStatus newStatus = after;

        // 写透 Redis 快照（非事实源，审计/观测用）
        persist.save(newStatus, extended, candidateId);

        log.info("[FSM Service] candidateId={} event={} → {} (state: {})",
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
