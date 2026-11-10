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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 新增：流式聊天控制器
 * <p>
 * 提供 4 个接口：
 * - POST /api/chat/stream            流式多轮对话（带记忆）
 * - POST /api/creative/stream        流式创意写作（无记忆，可选 sessionId 纳入取消管理）
 * - POST /api/chat/stream/cancel     取消正在进行的生成（chat 流全量取消；creative 流需带 sessionId 才纳入管理）
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

        // 注册会话（便于主动取消）——返回本次流的唯一 id，同一 session 可并发多条
        String streamId = sessionManager.register(sessionId, emitter);

        // 心跳（防 Nginx 超时）—— 同时拿到 scheduler，cleanup 里要 shutdown 否则线程泄漏
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-" + streamId);
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> heartbeat = startHeartbeat(heartbeatScheduler, emitter);

        // 清理钩子：必须 shutdown scheduler，否则每路流都会泄漏一个线程。
        // AtomicBoolean 防重：onError 之后容器还会走 onCompletion，超时路径同理，
        // 同一路流的 cleanup 可能被触发多次
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            heartbeat.cancel(true);
            heartbeatScheduler.shutdownNow();
            sessionManager.unregister(streamId);
        };
        emitter.onCompletion(cleanup);
        // 超时路径：容器在调 onTimeout 之前已把 emitter 完成标志置位
        // （ResponseBodyEmitter.DefaultCallback 先 compareAndSet 再执行回调），
        // 回调里再调 complete() 是 no-op，AsyncRequestTimeoutException 压不住。
        // onTimeout 的价值在 cleanup：停心跳、注销流
        emitter.onTimeout(() -> {
            log.warn("SSE timeout for stream {}", streamId);
            cleanup.run();
        });
        emitter.onError(e -> {
            log.error("SSE error for stream {}", streamId, e);
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
                    // 传输完成输出。
                    // tokenUsage / finishReason 可能为 null（部分厂商流式响应不带 usage、
                    // 异常中断时 finishReason 缺失），Map.of 不允许 null 值，缺失字段直接不输出
                    .onCompleteResponse(chatResponse -> {
                        Map<String, Object> done = new LinkedHashMap<>();
                        done.put("sessionId", sessionId);
                        if (chatResponse.tokenUsage() != null) {
                            done.put("totalTokens", chatResponse.tokenUsage().totalTokenCount());
                        }
                        if (chatResponse.finishReason() != null) {
                            done.put("finishReason", chatResponse.finishReason().name());
                        }
                        sendSafely(emitter, "done", done);
                        cleanup.run();
                        safeComplete(emitter);
                    })
                    // 异常输出
                    .onError(err -> {
                        log.error("Stream error for stream {}", streamId, err);
                        sendSafely(emitter, "error", Map.of(
                                "message", "生成失败，请重试",
                                "sessionId", sessionId
                        ));
                        cleanup.run();
                        // 用 complete 而不是 completeWithError：
                        // completeWithError 会把 err 再次分派给 emitter.onError，导致 cleanup 重复执行；
                        // （日志里的 AsyncRequestTimeoutException 是超时路径的产物，与 completeWithError 无关）
                        safeComplete(emitter);
                    })
                    .start();
        } catch (Exception e) {
            log.error("Failed to start stream for {}", streamId, e);
            cleanup.run();
            safeComplete(emitter);
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

        // 可选 sessionId：前端传入后本路流纳入 sessionManager 管理，可被 /chat/stream/cancel 取消；
        // 不传则保持原有行为（仅流式输出，不可取消、不计入 activeCount）。
        // 用三元赋值保证 effectively final，下方 lambda 才能引用
        final String streamId = req.sessionId() != null
                ? sessionManager.register(String.valueOf(req.sessionId()), emitter)
                : null;

        // 心跳：早期版本没有心跳，60s 超时撞上 Nginx 默认 proxy_read_timeout=60s 时，
        // 慢模型（推理链长）会被网关静默掐断且服务端无感知
        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat-creative-" + (streamId != null ? streamId : req.topic()));
            t.setDaemon(true);
            return t;
        });
        ScheduledFuture<?> heartbeat = startHeartbeat(heartbeatScheduler, emitter);

        // cleanup 防重（同 chatStream）
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            heartbeat.cancel(true);
            heartbeatScheduler.shutdownNow();
            if (streamId != null) {
                sessionManager.unregister(streamId);
            }
        };

        // 超时和异常回调与 chatStream 对齐。
        // 注意：超时路径上 complete() 是 no-op（容器先置位完成标志再调回调），
        // AsyncRequestTimeoutException 不可避免；onTimeout 的价值在 cleanup
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            log.warn("SSE timeout for creative stream, topic={}", req.topic());
            cleanup.run();
        });
        emitter.onError(e -> {
            log.error("SSE error for creative stream, topic={}", req.topic(), e);
            cleanup.run();
        });

        try {
            streamingChatAssistant.creativeStream(req.topic())
                    .onPartialResponse(partial -> sendSafely(emitter, "message",
                            Map.of("content", partial)))
                    .onCompleteResponse(chatResponse -> {
                        // tokenUsage 可能为 null（部分厂商流式响应不带 usage），Map.of 不允许 null 值
                        Map<String, Object> done = new LinkedHashMap<>();
                        done.put("topic", req.topic());
                        if (chatResponse.tokenUsage() != null) {
                            done.put("totalTokens", chatResponse.tokenUsage().totalTokenCount());
                        }
                        sendSafely(emitter, "done", done);
                        cleanup.run();
                        safeComplete(emitter);
                    })
                    .onError(err -> {
                        log.error("Creative stream error, topic={}", req.topic(), err);
                        sendSafely(emitter, "error", Map.of("message", "生成失败"));
                        cleanup.run();
                        safeComplete(emitter);
                    })
                    .start();
        } catch (Exception e) {
            log.error("Failed to start creative stream, topic={}", req.topic(), e);
            cleanup.run();
            safeComplete(emitter);
        }

        return emitter;
    }

    /**
     * 取消正在进行的生成
     */
    @PostMapping("/chat/stream/cancel")
    public Map<String, Object> cancel(@RequestParam String sessionId) {
        boolean cancelled = sessionManager.cancel(sessionId);
        // 文案必须诚实：cancel 只 complete emitter（断开客户端连接），
        // TokenStream 没有 cancel()，模型可能在后台继续生成并计费
        return Map.of(
                "sessionId", sessionId,
                "cancelled", cancelled,
                "note", cancelled ? "已断开连接；模型可能在后台继续生成并计费" : "会话不存在或已完成"
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
                safeComplete(emitter);
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

    /**
     * 安全关闭。
     * <p>注意：Spring 的 complete() 本身是幂等的（ResponseBodyEmitter 内 compareAndSet 守卫），
     * 重复调用不会抛异常；真正会抛 IllegalStateException 的是 complete 之后再 send()
     * （"ResponseBodyEmitter has already completed"），那条路径由 sendSafely 捕获。
     * 本方法是防御式写法，保留无害。
     */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed: {}", e.getMessage());
        }
    }

    // ============== DTOs ==============

    public record ChatStreamRequest(Long sessionId, String message) {
    }

    /**
     * @param topic     创作主题
     * @param sessionId 可选：传入后本路流注册进 sessionManager，可被 /chat/stream/cancel 取消
     */
    public record CreativeStreamRequest(String topic, Long sessionId) {
    }
}
