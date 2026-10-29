package com.example.assistant.controller;

import com.example.assistant.assistant.StreamingChatAssistant;
import com.example.assistant.config.StreamSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 新增：流式聊天控制器
 * <p>
 * 提供 4 个接口：
 * - POST /api/chat/stream            流式多轮对话（带记忆）
 * - POST /api/creative/stream        流式创意写作（无记忆）
 * - POST /api/chat/stream/cancel     取消正在进行的生成
 * - GET  /api/chat/stream/active     查看活跃流式会话数
 * <p>
 * 前端调用方式（fetch + ReadableStream）：
 * <pre>{@code
 * const response = await fetch('/api/chat/stream', {
 * method: 'POST',
 * headers: { 'Content-Type': 'application/json' },
 * body: JSON.stringify({ sessionId: 1, message: '你好' })
 * });
 * const reader = response.body.getReader();
 * // ... 解析 SSE 流
 * }</pre>
 * @author hengdboy
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatStreamController {

    private final StreamingChatAssistant streamingChatAssistant;
    private final StreamSessionManager sessionManager;

    /**
     * 流式多轮对话
     */
    @PostMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatStreamRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String sessionId = String.valueOf(req.sessionId());

        // 注册会话（便于主动取消）
        sessionManager.register(sessionId, emitter);

        // 心跳（防 Nginx 超时）—— 同时拿到 scheduler，cleanup 里要 shutdown 否则线程泄漏
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> heartbeat = startHeartbeat(heartbeatScheduler, emitter);

        // 清理钩子：必须 shutdown scheduler，否则每路流都会泄漏一个线程
        Runnable cleanup = () -> {
            heartbeat.cancel(true);
            heartbeatScheduler.shutdownNow();
            sessionManager.unregister(sessionId);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            log.warn("SSE timeout for session {}", sessionId);
            cleanup.run();
        });
        emitter.onError(e -> {
            log.error("SSE error for session {}", sessionId, e);
            cleanup.run();
        });

        // 启动流
        try {

            streamingChatAssistant.chatStream(req.sessionId(), req.message())
                    // 增量输出
                    .onPartialResponse(partial -> sendSafely(emitter, "message",
                            Map.of("content", partial)))
                    // 思考输出
                    .onPartialThinking(think -> {
                        sendSafely(emitter, "thinking", think);
                    })
                    // 传输完成输出
                    .onCompleteResponse(chatResponse -> {
                        sendSafely(emitter, "done", Map.of(
                                "sessionId", sessionId,
                                "totalTokens", chatResponse.tokenUsage().totalTokenCount(),
                                "finishReason", chatResponse.finishReason().name()
                        ));
                        cleanup.run();
                        emitter.complete();
                    })
                    // 异常输出
                    .onError(err -> {
                        log.error("Stream error for session {}", sessionId, err);
                        sendSafely(emitter, "error", Map.of(
                                "message", "生成失败，请重试",
                                "sessionId", sessionId
                        ));
                        cleanup.run();
                        emitter.completeWithError(err);
                    })
                    .start();
        } catch (Exception e) {
            log.error("Failed to start stream", e);
            cleanup.run();
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 流式创意写作
     */
    @PostMapping(value = "/creative/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter creativeStream(@RequestBody CreativeStreamRequest req) {
        SseEmitter emitter = new SseEmitter(60_000L);

        try {
            streamingChatAssistant.creativeStream(req.topic())
                    .onPartialResponse(partial -> sendSafely(emitter, "message",
                            Map.of("content", partial)))
                    .onCompleteResponse(chatResponse -> {
                        sendSafely(emitter, "done", Map.of(
                                "topic", req.topic(),
                                "totalTokens", chatResponse.tokenUsage().totalTokenCount()
                        ));
                        emitter.complete();
                    })
                    .onError(err -> {
                        sendSafely(emitter, "error", Map.of("message", "生成失败"));
                        emitter.completeWithError(err);
                    })
                    .start();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 取消正在进行的生成
     */
    @PostMapping("/chat/stream/cancel")
    public Map<String, Object> cancel(@RequestParam String sessionId) {
        boolean cancelled = sessionManager.cancel(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "cancelled", cancelled,
                "note", cancelled ? "已取消，模型会尽快停止" : "会话不存在或已完成"
        );
    }

    /**
     * 监控：当前活跃流式会话数
     */
    @GetMapping("/chat/stream/active")
    public Map<String, Object> activeCount() {
        return Map.of("active", sessionManager.activeCount());
    }

    // ============== 内部工具方法 ==============

    /**
     * SSE 心跳：每 15s 推一个注释行（: ping），让 Nginx/ALB 不超时断开
     */
    private ScheduledFuture<?> startHeartbeat(ScheduledExecutorService scheduler, SseEmitter emitter) {
        return scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                emitter.complete();
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    /**
     * 安全的 SSE 推送：客户端断开时不能再 send
     */
    private void sendSafely(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed: {}", e.getMessage());
        }
    }

    // ============== DTOs ==============

    public record ChatStreamRequest(Long sessionId, String message) {
    }

    public record CreativeStreamRequest(String topic) {
    }
}