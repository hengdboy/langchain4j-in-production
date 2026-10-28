package com.example.sdr.config;

import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.statemachine.data.redis.RedisStateMachineContextRepository;

/**
 * Ch24 FSM Redis 上下文仓库配置。
 *
 * <h2>为什么只用 ContextRepository，不用 Persister</h2>
 * <p>Spring StateMachine 4.x 的 {@code RedisStateMachinePersister} 依赖
 * {@code StateMachinePersist} 接口，而 Redis 持久化需要
 * {@code RedisRepositoryStateMachinePersist}（用 {@code RedisStateMachineRepository}
 * 不是 {@code RedisStateMachineContextRepository}），结构复杂。
 *
 * <p>教学版简化：<strong>直接用 {@link RedisStateMachineContextRepository}
 * 读写 Context</strong>，不绕 Persister。
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
@Configuration
public class StateMachineRedisConfig {

    /**
     * Redis StateMachine 上下文仓库（实际读写 Redis）。
     * Key: sm:{machineId}，Value: 序列化的 StateMachineContext
     */
    @Bean
    public RedisStateMachineContextRepository<LeadStatus, FsmEvent> redisStateMachineContextRepository(
            RedisConnectionFactory connectionFactory) {
        return new RedisStateMachineContextRepository<>(connectionFactory);
    }
}