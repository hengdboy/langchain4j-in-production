package com.example.sdr.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateMachineContext;
import org.springframework.statemachine.data.redis.RedisStateMachineContextRepository;
import org.springframework.statemachine.support.DefaultExtendedState;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Ch24 FSM StateMachine Redis 持久化（写透快照，非事实源）。
 *
 * <h2>事实源说明（v0.25 修订）</h2>
 * <p><b>MySQL 的 {@code lead.status} 是状态机当前状态的唯一事实源</b>——每次处理事件前
 * 用 DB 状态 {@code resetStateMachine}，因此 Redis 这里只承担<b>写透快照</b>职责
 * （审计 / 观测 / 未来恢复参考），不再负责恢复运行态。
 *
 * <h2>ARCHITECTURE.md §5 对应</h2>
 * <pre>
 *   sm:{leadId} → JSON 序列化的 StateMachineContext
 *   TTL: 1 小时（spring-statemachine-data-redis 内置）
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadStateMachinePersist {

    private final RedisStateMachineContextRepository<LeadStatus, FsmEvent> repository;

    /**
     * 写入 Redis：machineId → 序列化 StateMachineContext（状态 + ExtendedState 快照）。
     */
    public void save(LeadStatus currentState, Map<String, Object> extended, Long leadId) {
        DefaultStateMachineContext<LeadStatus, FsmEvent> ctx =
                new DefaultStateMachineContext<>(
                        currentState, null, new HashMap<>(),
                        new DefaultExtendedState(extended == null ? new HashMap<>() : new HashMap<>(extended)));
        repository.save(ctx, leadId.toString());
        log.debug("💾 [FSM Persist] saved leadId={} state={} extendedKeys={}",
                leadId, currentState, ctx.getExtendedState().getVariables().size());
    }

    /**
     * 从 Redis 读取 StateMachineContext（可能为 null）。
     */
    public StateMachineContext<LeadStatus, FsmEvent> load(Long leadId) {
        StateMachineContext<LeadStatus, FsmEvent> ctx =
                repository.getContext(leadId.toString());
        log.debug("📂 [FSM Persist] loaded leadId={} found={}",
                leadId, ctx != null);
        return ctx;
    }

    /**
     * 检查某 lead 是否有持久化记录。
     */
    public boolean exists(Long leadId) {
        return load(leadId) != null;
    }
}
