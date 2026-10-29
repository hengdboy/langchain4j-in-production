package com.example.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * @author hengdboy
 */
@Component
@Slf4j
public class StreamSessionManager {

    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    /**
     * 注册新的 SSE 会话
     */
    public void register(String sessionId, SseEmitter emitter) {
        sessions.put(sessionId, emitter);
        log.debug("SSE session registered: {} (active: {})", sessionId, sessions.size());
    }

    /**
     * 会话结束时注销
     */
    public void unregister(String sessionId) {
        SseEmitter removed = sessions.remove(sessionId);
        if (removed != null) {
            log.debug("SSE session unregistered: {} (active: {})", sessionId, sessions.size());
        }
    }

    /**
     * 主动取消指定会话
     *
     * @return true 表示成功取消，false 表示会话不存在
     */
    public boolean cancel(String sessionId) {
        SseEmitter emitter = sessions.remove(sessionId);
        if (emitter == null) {
            return false;
        }
        try {
            emitter.complete();
            log.info("SSE session cancelled: {}", sessionId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to cancel session {}", sessionId, e);
            return false;
        }
    }

    /**
     * 当前活跃 SSE 会话数（监控用）
     */
    public int activeCount() {
        return sessions.size();
    }
}