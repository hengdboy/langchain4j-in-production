package com.example.sdr.core;

import com.example.sdr.config.SdrProperties;
import com.example.sdr.service.LeadService;
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
 *   <li>从 Redis ZSet {@code sdr:delayed-events} 取到期事件（score ≤ now）</li>
 *   <li>对每个事件调用 {@link LeadService#processEvent(Long, FsmEvent)}
 *       —— 统一副作用入口（DB 更新 + 轮次 + 延迟事件重排 + 审计）</li>
 * </ol>
 *
 * <h2>v0.25 修改</h2>
 * <p>原直连 {@code fsmService.sendEvent} 绕过 LeadService 的 DB 同步与延迟事件重排逻辑，
 * 现统一走 {@link LeadService}（业务副作用只在一处）。
 *
 * <h2>扫描频率可配（sdr.scheduler.step1-scan-interval-ms）</h2>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelayedEventWorker {

    private final DelayedEventQueue queue;
    private final LeadService leadService;
    private final SdrProperties props;

    /**
     * 每 {@code sdr.scheduler.step1-scan-interval-ms} 毫秒扫一次。
     *
     * <p>教学版默认 60s（1 分钟）—— 1 分钟误差对 72h 静默期完全可接受。
     */
    @Scheduled(fixedDelayString = "${sdr.scheduler.step1-scan-interval-ms:60000}")
    public void processDueEvents() {
        var events = queue.takeDueEvents();
        if (events.isEmpty()) {
            return;
        }
        log.info("⏰ [DelayedEvent] processing {} due events", events.size());

        for (DelayedEventQueue.DelayedEvent event : events) {
            try {
                // 统一走 LeadService：TIMEOUT_72H 由 processEvent 内部路由到 handleTimeout
                leadService.processEvent(event.leadId(), event.event());
            } catch (Exception e) {
                log.error("❌ [DelayedEvent] leadId={} event={} failed",
                        event.leadId(), event.event(), e);
            }
        }
    }

    /**
     * 手动触发：给指定 lead 调度一轮静默期超时事件（教学测试用）。
     */
    public void schedule72hTimeout(Long leadId) {
        queue.schedule(leadId, FsmEvent.TIMEOUT_72H,
                Instant.now().plus(props.stepWaitTimeout()));
    }
}
