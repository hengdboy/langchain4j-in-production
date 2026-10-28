package com.example.hiring.core;

import com.example.hiring.config.HiringProperties;
import com.example.hiring.service.CandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ch24 FSM 时间事件扫描器（每 N 秒从 Redis ZSet 取到期事件）。
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>从 Redis ZSet {@code hiring:delayed-events} 取到期事件（score ≤ now）</li>
 *   <li>对每个事件调用 {@link CandidateService#processEvent}
 *       —— 统一副作用入口（DB 更新 + 轮次 + 延迟事件重排 + 审计）</li>
 * </ol>
 *
 * <h2>超时事件的前置判断在 CandidateService</h2>
 * <p>REPLY_TIMEOUT_48H → handleReplyTimeout（追发 or 转 CLOSED_NO_RESPONSE）；
 * OFFER_TIMEOUT_5D → handleOfferTimeout（催办 or 转 EXPIRED）。
 * 扫描器只负责"把到期的包递过去"，不做业务判断。
 *
 * <h2>扫描频率可配（hiring.scheduler.event-scan-interval-ms）</h2>
 *
 * @author langchain4j-tutorial
 * @since v0.23 → v0.30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedEventWorker {

    private final DelayedEventQueue queue;
    private final CandidateService candidateService;
    private final HiringProperties props;

    /**
     * 每 {@code hiring.scheduler.event-scan-interval-ms} 毫秒扫一次。
     *
     * <p>教学版默认 60s（1 分钟）—— 1 分钟误差对 48h/5d 等待期完全可接受。
     */
    @Scheduled(fixedDelayString = "${hiring.scheduler.event-scan-interval-ms:60000}")
    public void processDueEvents() {
        var events = queue.takeDueEvents();
        if (events.isEmpty()) {
            return;
        }
        log.info("⏰ [DelayedEvent] processing {} due events", events.size());

        for (DelayedEventQueue.DelayedEvent event : events) {
            try {
                // 统一走 CandidateService：超时事件在其内部路由（追发/催办/终态）
                candidateService.processEvent(event.candidateId(), event.event());
            } catch (Exception e) {
                log.error("❌ [DelayedEvent] candidateId={} event={} failed",
                        event.candidateId(), event.event(), e);
            }
        }
    }

    /**
     * 手动触发：给指定候选人调度一轮约面邀请超时事件（教学测试用）。
     */
    public void scheduleReplyTimeout(Long candidateId) {
        queue.schedule(candidateId, FsmEvent.REPLY_TIMEOUT_48H,
                Instant.now().plus(props.replyTimeout()));
    }
}
