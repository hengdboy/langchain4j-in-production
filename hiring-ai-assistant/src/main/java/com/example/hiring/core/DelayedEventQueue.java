package com.example.hiring.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * FSM 时间事件队列：基于 Redis ZSet 的延迟队列。
 *
 * <h2>为什么用 Redis ZSet</h2>
 * <ul>
 * <li>Spring StateMachine 4.x 内置 Timer <b>不能 cancel/reschedule</b></li>
 * <li>客户中途回信时，必须取消 72h 定时器（否则误触发追发）</li>
 * <li>ZSet 的 score = 触发时间戳，{@code ZRANGEBYSCORE 0 now} 取到期事件</li>
 * </ul>
 *
 * <h2>Key 设计</h2>
 * <pre>
 * hiring:delayed-events
 * member: "{candidateId}:{FsmEvent.name()}"
 * score:  触发时间戳（毫秒）
 * </pre>
 *
 * <h2>原子性</h2>
 * <p>{@link #takeDueEvents()} 用 {@code rangeByScore} + {@code remove} 两步操作。
 * 教学版接受少量并发误差；生产可改为 Lua 脚本做严格原子。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedEventQueue {

    /** ZSet Key 前缀 */
    public static final String ZSET_KEY = "hiring:delayed-events";

    private final StringRedisTemplate redis;

    /**
     * 调度一个事件在未来某时刻触发。
     *
     * @param candidateId  销售线索 ID（作为 machineId）
     * @param event   要触发的事件
     * @param fireAt  触发时间（绝对时间）
     */
    public void schedule(Long candidateId, FsmEvent event, Instant fireAt) {
        String member = encode(candidateId, event);
        Double score = (double) fireAt.toEpochMilli();
        redis.opsForZSet().add(ZSET_KEY, member, score);
        log.debug("[DelayedEvent] scheduled lead={} event={} fireAt={}",
                candidateId, event, fireAt);
    }

    /**
     * 取消已调度的事件（用于客户中途回信时清理定时器）。
     *
     * @param candidateId 销售线索 ID
     * @param event   要取消的事件
     * @return true 表示成功取消，false 表示原本就不存在
     */
    public boolean cancel(Long candidateId, FsmEvent event) {
        String member = encode(candidateId, event);
        Long removed = redis.opsForZSet().remove(ZSET_KEY, member);
        log.debug("[DelayedEvent] cancelled lead={} event={} removed={}",
                candidateId, event, removed);
        return removed != null && removed > 0;
    }

    /**
     * 取所有到期事件（score ≤ now），并原子地从 ZSet 中移除。
     *
     * <p>实际是两步：先 rangeByScore 查所有到期，再 remove。
     * 教学版可接受微小重复触发；生产用 Lua 脚本保证严格原子性。
     */
    public List<DelayedEvent> takeDueEvents() {
        long now = System.currentTimeMillis();
        Set<String> members = redis.opsForZSet().rangeByScore(ZSET_KEY, 0, now);
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        // 移除已取的（防止重复触发）
        redis.opsForZSet().remove(ZSET_KEY, members.toArray());

        List<DelayedEvent> events = new ArrayList<>(members.size());
        for (String m : members) {
            try {
                events.add(decode(m));
            } catch (Exception e) {
                log.warn(" [DelayedEvent] skip malformed member={}", m, e);
            }
        }
        return events;
    }

    /**
     * 查询某 lead 的所有待触发事件（调试用）。
     */
    public List<DelayedEvent> peekByLead(Long candidateId) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(ZSET_KEY, 0, Long.MAX_VALUE);
        if (tuples == null) return List.of();
        return tuples.stream()
                .map(t -> {
                    try {
                        return decode(t.getValue());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(e -> e != null && e.candidateId().equals(candidateId))
                .toList();
    }

    /**
     * 清空所有延迟事件（仅用于测试 / 故障恢复）。
     */
    public void clearAll() {
        redis.delete(ZSET_KEY);
        log.warn("[DelayedEvent] cleared all events");
    }

    /** 编码 member = "candidateId:eventName"。 */
    private static String encode(Long candidateId, FsmEvent event) {
        return candidateId + ":" + event.name();
    }

    /** 解码 member → DelayedEvent(candidateId, event)。 */
    private static DelayedEvent decode(String member) {
        int idx = member.indexOf(':');
        if (idx <= 0 || idx >= member.length() - 1) {
            throw new IllegalArgumentException("invalid member: " + member);
        }
        Long candidateId = Long.parseLong(member.substring(0, idx));
        FsmEvent event = FsmEvent.valueOf(member.substring(idx + 1));
        return new DelayedEvent(candidateId, event);
    }

    /** 延迟事件记录（candidateId + event）。 */
    public record DelayedEvent(Long candidateId, FsmEvent event) {}
}