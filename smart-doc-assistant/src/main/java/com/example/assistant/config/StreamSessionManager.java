package com.example.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理进行中的 SSE 会话，支持"用户主动取消生成"。
 *
 * 使用场景：
 * - 用户在网页上点"停止生成"按钮 → 前端调用 /api/chat/stream/cancel
 * - 服务端找到对应的 SseEmitter，调 complete() 关闭连接
 * - 底层流式调用会收到 IOException，框架中断后续处理
 *
 * 注意：这只关闭了客户端连接，并不能真的"杀掉"LLM 厂商的请求。
 * LLM 仍会生成到下一个 token 到达为止，但不会再写到客户端。
 *
 * key 设计：早期版本直接用 sessionId 当 key，同一个用户开两个标签页时
 * 后一个会覆盖前一个，旧连接永远泄漏且无法取消。现在用 sessionId#序号
 * 作为 streamId，一个 session 可以同时挂多条流，取消时按前缀批量处理。
 *
 * @author hengdboy
 */
@Component
@Slf4j
public class StreamSessionManager {

    /** key = streamId（sessionId#自增序号），value = 对应的 emitter */
    private final Map<String, SseEmitter> streams = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong();

    /**
     * 注册新的 SSE 流，返回本次流的唯一 id
     *
     * @return streamId，注销时需要原样传回
     */
    public String register(String sessionId, SseEmitter emitter) {
        String streamId = sessionId + "#" + seq.incrementAndGet();
        streams.put(streamId, emitter);
        log.debug("SSE stream registered: {} (active: {})", streamId, streams.size());
        return streamId;
    }

    /**
     * 单条流结束时注销
     */
    public void unregister(String streamId) {
        SseEmitter removed = streams.remove(streamId);
        if (removed != null) {
            log.debug("SSE stream unregistered: {} (active: {})", streamId, streams.size());
        }
    }

    /**
     * 取消某个 session 下所有进行中的流
     *
     * @return true 表示至少取消了一条
     */
    public boolean cancel(String sessionId) {
        String prefix = sessionId + "#";
        boolean cancelled = false;

        // 先拷贝 key 快照，避免遍历过程中 remove 影响迭代
        for (String streamId : new ArrayList<>(streams.keySet())) {
            if (!streamId.startsWith(prefix)) {
                continue;
            }
            SseEmitter emitter = streams.remove(streamId);
            if (emitter == null) {
                continue;
            }
            try {
                emitter.complete();
                cancelled = true;
                log.info("SSE stream cancelled: {}", streamId);
            } catch (Exception e) {
                log.warn("Failed to cancel stream {}", streamId, e);
            }
        }
        return cancelled;
    }

    /**
     * 当前活跃 SSE 流数（监控用）
     */
    public int activeCount() {
        return streams.size();
    }
}
