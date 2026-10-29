package com.example.hiring.service;

import com.example.hiring.config.HiringProperties;
import com.example.hiring.core.CandidateEvent;
import com.example.hiring.core.CandidateStateMachineService;
import com.example.hiring.core.CandidateStatus;
import com.example.hiring.core.CandidateTransitionedEvent;
import com.example.hiring.core.DelayedEventQueue;
import com.example.hiring.core.FsmEvent;
import com.example.hiring.core.CandidateContext;
import com.example.hiring.entity.SentEmail;
import com.example.hiring.mapper.CandidateEventMapper;
import com.example.hiring.mapper.CandidateMapper;
import com.example.hiring.mapper.SentEmailMapper;
import com.example.hiring.tools.MailGateway;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 业务层：候选人状态变更的统一入口。
 *
 * <h2>职责</h2>
 * <ul>
 * <li>封装状态变更的所有副作用（DB 更新、延迟队列调度/取消、审计落库）</li>
 * <li>让 Agent / Controller 只调 {@link #processEvent}，不直接接触 StateMachine</li>
 * <li>超时类事件的<b>业务前置判断</b>：{@link #handleReplyTimeout}（追发 or 关闭）、
 * {@link #handleOfferTimeout}（催办 or 过期）——这两个事件不进状态机，
 * 原因见 {@link FsmEvent} 类注释（自循环坑）</li>
 * </ul>
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {

    private final CandidateMapper candidateMapper;
    private final CandidateEventMapper candidateEventMapper;
    private final CandidateStateMachineService fsmService;
    private final DelayedEventQueue delayedQueue;
    private final HiringProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final MailGateway mailGateway;
    private final SentEmailMapper sentEmailMapper;

    /** 处理业务事件（无额外上下文，供 DelayedEventWorker / 简单场景使用）。 */
    public CandidateContext processEvent(Long candidateId, FsmEvent event) {
        return processEvent(candidateId, event, Map.of());
    }

    /**
     * 处理业务事件：读候选人 → 状态机处理 → 写回 → 调度/取消延迟事件 → 审计落库。
     *
     * @param candidateId 候选人 ID
     * @param event       要处理的事件
     * @param overrides   额外写入 ExtendedState 的键值（verdict / replyDecision /
     * evalOutcome / offerDecision / humanApproved / matchScore）
     * @return 更新后的 CandidateContext（Guard 拒绝则原样返回）
     */
    public CandidateContext processEvent(Long candidateId, FsmEvent event, Map<String, Object> overrides) {
        log.info("[CandidateService] processing candidateId={} event={} overrides={}",
                candidateId, event, overrides);

        // 超时类事件：业务前置判断（追发/催办 or 终态），不走 FSM 自循环
        if (event == FsmEvent.REPLY_TIMEOUT_48H) {
            return handleReplyTimeout(candidateId);
        }
        if (event == FsmEvent.OFFER_TIMEOUT_5D) {
            return handleOfferTimeout(candidateId);
        }

        // 读候选人（MySQL）
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate not found: " + candidateId);
        }
        CandidateStatus oldStatus = candidate.getStatus();

        // 应用覆盖字段（verdict / matchScore 等持久化字段）
        applyOverrides(candidate, overrides);

        // 状态机处理（同机 ExtendedState + DB 状态重置，返回新状态或 null）
        CandidateStatus newStatus = fsmService.sendEvent(candidate, event, overrides);

        // 拒绝则原样返回（不改库）
        if (newStatus == null) {
            log.warn("[CandidateService] event {} rejected by StateMachine, candidate unchanged", event);
            return candidate;
        }

        // 更新状态 + 副作用（轮次/延迟事件/审计）
        candidate.setStatus(newStatus);
        handleSideEffects(event, candidate, oldStatus, newStatus);
        candidate.setUpdatedAt(Instant.now());
        candidateMapper.updateById(candidate);
        recordEvent(candidate, event, oldStatus, newStatus);

        // 发布状态迁移事件（事件驱动编排：CandidatePipelineService 监听并触发下一个 Agent）
        eventPublisher.publishEvent(new CandidateTransitionedEvent(candidateId, oldStatus, newStatus, event));

        log.info("[CandidateService] candidateId={} {} → {}", candidateId, oldStatus, newStatus);
        return candidate;
    }

    /**
     * 处理约面邀请超时（REPLY_TIMEOUT_48H，由 DelayedEventWorker 触发）。
     *
     * <ul>
     * <li>inviteRounds &lt; maxInviteRounds → 追发一轮（rounds+1，重排 48h 监听）</li>
     * <li>inviteRounds ≥ max → 状态机发 MAX_ROUNDS_REACHED → CLOSED_NO_RESPONSE</li>
     * </ul>
     *
     * @return 更新后的 CandidateContext（若已不在 INVITE_SENT 则原样返回，忽略过期超时）
     */
    public CandidateContext handleReplyTimeout(Long candidateId) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.INVITE_SENT) {
            log.info("[CandidateService] candidateId={} reply-timeout ignored (status={})",
                    candidateId, candidate == null ? null : candidate.getStatus());
            return candidate;
        }

        int rounds = candidate.getInviteRounds() == null ? 0 : candidate.getInviteRounds();
        if (rounds < props.getFsm().getMaxInviteRounds()) {
            // 追发：同一封邀请再发一遍（rounds+1），并重排 48h 监听
            resendLatestInvite(candidate, rounds + 1);
            candidate.setInviteRounds(rounds + 1);
            candidate.setUpdatedAt(Instant.now());
            candidateMapper.updateById(candidate);
            schedule(candidateId, FsmEvent.REPLY_TIMEOUT_48H, props.replyTimeout());
            recordEvent(candidate, FsmEvent.REPLY_TIMEOUT_48H,
                    CandidateStatus.INVITE_SENT, CandidateStatus.INVITE_SENT);
            log.info("[CandidateService] candidateId={} invite follow-up round {}, next timeout scheduled",
                    candidateId, rounds + 1);
            return candidate;
        }

        // 已达最大轮次 → 状态机转 CLOSED_NO_RESPONSE
        log.info("[CandidateService] candidateId={} max invite rounds reached, closing",
                candidateId);
        return processEvent(candidateId, FsmEvent.MAX_ROUNDS_REACHED, Map.of());
    }

    /**
     * 处理 offer 超时（OFFER_TIMEOUT_5D，由 DelayedEventWorker 触发）。
     *
     * <ul>
     * <li>未催办过 → 发催办邮件（offerReminderSent=true，重排 5 天监听）</li>
     * <li>已催办过 → 状态机发 OFFER_EXPIRED → EXPIRED</li>
     * </ul>
     */
    public CandidateContext handleOfferTimeout(Long candidateId) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.OFFER_SENT) {
            log.info("[CandidateService] candidateId={} offer-timeout ignored (status={})",
                    candidateId, candidate == null ? null : candidate.getStatus());
            return candidate;
        }

        if (!candidate.offerReminded()) {
            sendOfferReminder(candidate);
            candidate.setOfferReminderSent(true);
            candidate.setUpdatedAt(Instant.now());
            candidateMapper.updateById(candidate);
            schedule(candidateId, FsmEvent.OFFER_TIMEOUT_5D, props.offerTimeout());
            recordEvent(candidate, FsmEvent.OFFER_TIMEOUT_5D,
                    CandidateStatus.OFFER_SENT, CandidateStatus.OFFER_SENT);
            log.info("[CandidateService] candidateId={} offer reminder sent, rescheduled", candidateId);
            return candidate;
        }

        log.info("[CandidateService] candidateId={} offer expired after reminder", candidateId);
        return processEvent(candidateId, FsmEvent.OFFER_EXPIRED, Map.of());
    }

    // ===================== 私有辅助 =====================

    /** 把 overrides 里能映射到 candidate 持久化字段的值写回（其余键只进 ExtendedState） */
    private void applyOverrides(CandidateContext candidate, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        if (overrides.get("verdict") instanceof String v) {
            candidate.setMatchVerdict(v);
        }
        if (overrides.get("matchScore") instanceof Integer score) {
            candidate.setMatchScore(score);
        }
        if (overrides.get("reasons") instanceof String reasons) {
            candidate.setMatchReasons(reasons);
        }
    }

    /** 状态变更的延迟事件副作用：邀请发出 → 排 48h；回复 → 取消；offer 发出 → 排 5d；答复 → 取消 */
    private void handleSideEffects(FsmEvent event, CandidateContext candidate,
                                   CandidateStatus oldStatus, CandidateStatus newStatus) {
        Long candidateId = candidate.getId();

        switch (event) {
            case INVITE_SENT -> {
                if (newStatus == CandidateStatus.INVITE_SENT) {
                    // 首发记 1 轮（submit 时初始化为 0；这里兜底 max(current,1)，重放安全）
                    int current = candidate.getInviteRounds() == null ? 0 : candidate.getInviteRounds();
                    candidate.setInviteRounds(Math.max(current, 1));
                    schedule(candidateId, FsmEvent.REPLY_TIMEOUT_48H, props.replyTimeout());
                }
            }
            case INVITE_REPLIED -> {
                if (oldStatus == CandidateStatus.INVITE_SENT) {
                    delayedQueue.cancel(candidateId, FsmEvent.REPLY_TIMEOUT_48H);
                    log.info("[CandidateService] cancelled REPLY_TIMEOUT_48H for candidateId={}", candidateId);
                }
            }
            case EVAL_PASS -> {
                if (newStatus == CandidateStatus.OFFER_SENT) {
                    candidate.setOfferReminderSent(false);
                    schedule(candidateId, FsmEvent.OFFER_TIMEOUT_5D, props.offerTimeout());
                }
            }
            case OFFER_REPLIED -> {
                if (oldStatus == CandidateStatus.OFFER_SENT) {
                    delayedQueue.cancel(candidateId, FsmEvent.OFFER_TIMEOUT_5D);
                    log.info("[CandidateService] cancelled OFFER_TIMEOUT_5D for candidateId={}", candidateId);
                }
            }
            default -> { }
        }
    }

    /** 追发：取最近一封邀请邮件原样重发（教学版；生产会换追发话术并计入 Bandit） */
    private void resendLatestInvite(CandidateContext candidate, int round) {
        SentEmail latest = sentEmailMapper.selectList(new QueryWrapper<SentEmail>()
                .eq("candidate_id", candidate.getId()).orderByDesc("id").last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (latest == null) {
            log.warn("[CandidateService] candidateId={} no invite email to resend", candidate.getId());
            return;
        }
        String messageId = mailGateway.send(candidate.getEmail(), "Re: " + latest.getSubject(),
                latest.getBody(), latest.getLanguage());
        insertSent(candidate, round, "Re: " + latest.getSubject(), latest.getBody(), messageId);
    }

    /** offer 催办（确定性模板：offer 是法律承诺文件，正文走固定模板不走 LLM） */
    private void sendOfferReminder(CandidateContext candidate) {
        String body = "您好 " + (candidate.getName() == null ? "候选人" : candidate.getName())
                + "：\n\n我们此前向您发出的 offer 仍在等待您的答复。如有任何疑问欢迎回复本邮件沟通；"
                + "若我们在一周内仍未收到答复，该职位将流转给其他候选人。\n\n—— 星航科技 招聘团队";
        String messageId = mailGateway.send(candidate.getEmail(), "【催办】关于您的 offer 答复", body, "zh");
        insertSent(candidate, 0, "【催办】关于您的 offer 答复", body, messageId);
    }

    private void insertSent(CandidateContext candidate, int round, String subject, String body, String messageId) {
        SentEmail email = new SentEmail();
        email.setCandidateId(candidate.getId());
        email.setRoundNo(round);
        email.setFromEmail("hiring@postbox.workbuddy.dev");
        email.setToEmail(candidate.getEmail());
        email.setSubject(subject);
        email.setBody(body);
        email.setLanguage("zh");
        email.setStatus("sent");
        email.setSmtpMessageId(messageId);
        email.setSentAt(Instant.now());
        sentEmailMapper.insert(email);
    }

    private void schedule(Long candidateId, FsmEvent event, Duration delay) {
        Instant fireAt = Instant.now().plus(delay);
        delayedQueue.schedule(candidateId, event, fireAt);
        log.info("[CandidateService] scheduled {} for candidateId={} at {}", event, candidateId, fireAt);
    }

    /** 审计：每次 accepted 转换写入 candidate_event 表（自循环追发也记一条，状态前后同值） */
    private void recordEvent(CandidateContext candidate, FsmEvent event,
                             CandidateStatus oldStatus, CandidateStatus newStatus) {
        CandidateEvent ev = new CandidateEvent();
        ev.setCandidateId(candidate.getId());
        ev.setFromStatus(oldStatus == null ? null : oldStatus.name());
        ev.setToStatus(newStatus.name());
        ev.setEvent(event.name());
        ev.setActor("system");
        ev.setMetadata(candidate.getExtras());
        ev.setCreatedAt(Instant.now());
        candidateEventMapper.insert(ev);
    }
}
