package com.example.hiring.service;

import com.example.hiring.agent.EvaluationAgent;
import com.example.hiring.agent.InterviewQuestionAgent;
import com.example.hiring.agent.InviteContentAgent;
import com.example.hiring.agent.MatchEvaluatorAgent;
import com.example.hiring.agent.ReplyClassifierAgent;
import com.example.hiring.agent.ResumeParserAgent;
import com.example.hiring.api.CreateCandidateRequest;
import com.example.hiring.config.HiringProperties;
import com.example.hiring.core.CandidateContext;
import com.example.hiring.core.CandidateStatus;
import com.example.hiring.core.CandidateTransitionedEvent;
import com.example.hiring.core.FsmEvent;
import com.example.hiring.entity.BanditOutcome;
import com.example.hiring.entity.Interview;
import com.example.hiring.entity.JobPosition;
import com.example.hiring.entity.ReceivedEmail;
import com.example.hiring.entity.SentEmail;
import com.example.hiring.evolution.CannedInviteTemplates;
import com.example.hiring.evolution.BanditOptimizer;
import com.example.hiring.guardrail.DecisionFieldFilter;
import com.example.hiring.guardrail.PromptInjectionGuardrail;
import com.example.hiring.mapper.BanditOutcomeMapper;
import com.example.hiring.mapper.InterviewMapper;
import com.example.hiring.mapper.JobPositionMapper;
import com.example.hiring.mapper.CandidateMapper;
import com.example.hiring.mapper.ReceivedEmailMapper;
import com.example.hiring.mapper.SentEmailMapper;
import com.example.hiring.observability.AgentCallContext;
import com.example.hiring.tools.CalendarTool;
import com.example.hiring.tools.MailGateway;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ch24 事件驱动编排：招聘业务流水线（核心）。
 *
 * <h2>设计：确定性 FSM + LLM 叶子</h2>
 * <p><b>编排不是 LLM 自主决定</b>，而是由 FSM 状态迁移驱动：
 * <ol>
 *   <li>外部事件（投递 / 候选人回复 / 面试官反馈 / HR 复核 / 超时）进入本服务或
 *       {@link CandidateService#processEvent}</li>
 *   <li>accepted 转换后发布 {@link CandidateTransitionedEvent}</li>
 *   <li>本服务监听迁移事件，按<b>新状态</b>确定性触发对应 Agent：
 *       SHORTLISTED → 邀请 Agent；INTERVIEW_SCHEDULED → 排期+出题；
 *       OFFER_SENT → offer 函（确定性模板）；其他 → 等待</li>
 *   <li>Agent 输出结构化结果，作为下一 FSM 事件的 overrides 回喂</li>
 * </ol>
 *
 * <pre>
 *   submit()                            reply()
 *     │                                   │
 *     ▼                                   ▼
 *   ResumeParser → DecisionFieldFilter   ReplyClassifier → INVITE_REPLIED
 *   → MatchEvaluator → SCREEN_PASS          ├─ confirmed → INTERVIEW_SCHEDULED（排期+出题）
 *     ├─ SHORTLISTED → 邀请 Agent → INVITE_SENT └─ declined → WITHDRAWN
 *     ├─ REVIEW → ON_HOLD（等 HR 复核）
 *     └─ REJECT → REJECTED（带理由）
 * </pre>
 *
 * <h2>真实 vs canned</h2>
 * <p>每个 Agent 调用先看 {@code hiring.demo.use-real-agent}（或请求级覆盖）：
 * true → 调真实 LLM Agent，失败兜底 canned；false → 直接走确定性规则（无 key 可跑）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidatePipelineService {

    private static final String FROM_EMAIL = "hiring@postbox.workbuddy.dev";
    private static final String COMPANY = "星航科技";
    private static final String INTERVIEWER = "interviewer-01";

    /**
     * 请求级 canned/真实覆盖开关（try/finally 清理，防线程复用串模式）。
     */
    private static final ThreadLocal<Boolean> MODE_OVERRIDE = new ThreadLocal<>();

    private final CandidateMapper candidateMapper;
    private final JobPositionMapper jobPositionMapper;
    private final SentEmailMapper sentEmailMapper;
    private final ReceivedEmailMapper receivedEmailMapper;
    private final InterviewMapper interviewMapper;
    private final CandidateService candidateService;
    private final HiringProperties props;
    private final ObjectMapper objectMapper;
    private final BanditOptimizer banditOptimizer;
    private final BanditOutcomeMapper banditOutcomeMapper;
    private final DecisionFieldFilter decisionFieldFilter;
    private final PromptInjectionGuardrail injectionGuardrail;

    private final ResumeParserAgent resumeParserAgent;
    private final MatchEvaluatorAgent matchEvaluatorAgent;
    private final InviteContentAgent inviteContentAgent;
    private final ReplyClassifierAgent replyClassifierAgent;
    private final InterviewQuestionAgent interviewQuestionAgent;
    private final EvaluationAgent evaluationAgent;

    private final CalendarTool calendarTool;
    private final MailGateway mailGateway;

    // ============================================================
    // 外部事件入口：投递 / 回复 / 面试反馈 / HR 复核 / DPO 修正
    // ============================================================

    /**
     * 候选人投递：入库 → 解析 → 决策脱敏 → 评估 → SCREEN_PASS/REVIEW/FAIL。
     * 后续（邀请/排期/出题）由迁移监听器自动推进。
     */
    public CandidateContext submit(CreateCandidateRequest req) {
        return submit(req, props.getDemo().isUseRealAgent());
    }

    public CandidateContext submit(CreateCandidateRequest req, boolean useRealAgent) {
        MODE_OVERRIDE.set(useRealAgent);
        try {
            return doSubmit(req);
        } finally {
            MODE_OVERRIDE.remove();
        }
    }

    private CandidateContext doSubmit(CreateCandidateRequest req) {
        JobPosition job = jobPositionMapper.selectById(req.jobId());
        if (job == null || !"OPEN".equals(job.getStatus())) {
            throw new IllegalArgumentException("职位不存在或已关闭: " + req.jobId());
        }
        Long dup = candidateMapper.selectCount(new QueryWrapper<CandidateContext>()
                .eq("email", req.email()).eq("job_id", req.jobId()));
        if (dup != null && dup > 0) {
            throw new IllegalStateException("该邮箱已投递过此职位: " + req.email());
        }

        CandidateContext candidate = new CandidateContext();
        candidate.setEmail(req.email());
        candidate.setName(req.name());
        candidate.setPhone(req.phone());
        candidate.setJobId(req.jobId());
        candidate.setStatus(CandidateStatus.APPLIED);
        candidate.setInviteRounds(0);
        candidate.setOfferReminderSent(false);
        candidate.setResumeText(req.resumeText());
        candidate.setCreatedAt(Instant.now());
        candidate.setUpdatedAt(Instant.now());
        candidateMapper.insert(candidate);
        log.info("🚀 [Pipeline] submit candidateId={} email={} jobId={} realAgent={}",
                candidate.getId(), candidate.getEmail(), req.jobId(), realAgentEnabled());

        // 2. 注入检测（确定性预检，对象是简历原文——不可信第三方文本，不依赖 LLM 在场）
        // ⚠️ 直接调 validate() 只会返回 FATAL 结果、不抛异常（抛异常是 AiServices 接线后的行为），
        //    所以这里必须检查返回值——这是"护栏裸用"与"挂 Agent 上"的行为差异，见 Ch25 §25.3。
        var injectionResult = injectionGuardrail.validate(
                UserMessage.from(candidate.getResumeText()));
        if (!injectionResult.result().name().startsWith("SUCCESS")) {
            log.warn("🚨 [Pipeline] candidateId={} injection detected in resume, cold-reject: {}",
                    candidate.getId(), injectionResult.result());
            candidateService.processEvent(candidate.getId(), FsmEvent.SCREEN_FAIL, Map.of(
                    "verdict", "REJECT", "matchScore", 0,
                    "reasons", "[\"简历内容触发注入检测，按冷处理策略淘汰\"]"));
            return candidateMapper.selectById(candidate.getId());
        }

        // 3. 简历解析（只提取，不评估）
        ResumeParserAgent.ParsedProfile profile = doParse(candidate);
        if (candidate.getName() == null && profile.name() != null) candidate.setName(profile.name());
        if (candidate.getPhone() == null && profile.phone() != null) candidate.setPhone(profile.phone());
        candidateMapper.updateById(candidate);

        // 4. 决策脱敏（白名单过滤：年龄/性别/婚育/籍贯/联系方式根本进不了评估 prompt）
        String fullProfileJson = toJson(profile);
        String redactedJson = decisionFieldFilter.redact(fullProfileJson);
        candidate.setRedactedProfile(redactedJson);
        candidateMapper.updateById(candidate);
        log.info("🛡 [Pipeline] candidateId={} decision profile redacted: {} chars → {} chars",
                candidate.getId(), fullProfileJson.length(), redactedJson.length());

        // 5. 匹配评估
        MatchEvaluatorAgent.MatchVerdict verdict = doEvaluate(candidate, job);
        String normalized = normalizeVerdict(verdict.verdict());
        String reasonsJson = toJson(verdict.reasons() == null ? List.of() : verdict.reasons());

        FsmEvent screenEvent = switch (normalized) {
            case "SHORTLIST" -> FsmEvent.SCREEN_PASS;
            case "REJECT" -> FsmEvent.SCREEN_FAIL;
            default -> FsmEvent.SCREEN_REVIEW;
        };
        candidateService.processEvent(candidate.getId(), screenEvent, Map.of(
                "verdict", normalized, "matchScore",
                verdict.score() == null ? 0 : verdict.score(), "reasons", reasonsJson));
        return candidateMapper.selectById(candidate.getId());
    }

    /**
     * 候选人回复：按当前状态路由（INVITE_SENT → 约面答复；OFFER_SENT → offer 答复）。
     */
    public CandidateContext reply(Long candidateId, String replyText) {
        return reply(candidateId, replyText, props.getDemo().isUseRealAgent());
    }

    public CandidateContext reply(Long candidateId, String replyText, boolean useRealAgent) {
        MODE_OVERRIDE.set(useRealAgent);
        try {
            return doReply(candidateId, replyText);
        } finally {
            MODE_OVERRIDE.remove();
        }
    }

    private CandidateContext doReply(Long candidateId, String replyText) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate not found: " + candidateId);
        }
        if (candidate.getStatus() != CandidateStatus.INVITE_SENT
                && candidate.getStatus() != CandidateStatus.OFFER_SENT) {
            log.info("💬 [Pipeline] candidateId={} status={} not awaiting reply, ignore",
                    candidateId, candidate.getStatus());
            return candidate;
        }

        ReplyClassifierAgent.ReplyAssessment assessment = doClassify(candidate, replyText);
        String decision = normalizeDecision(assessment.decision());

        // 回信落库
        ReceivedEmail received = new ReceivedEmail();
        received.setCandidateId(candidateId);
        received.setFromEmail(candidate.getEmail());
        received.setSubject("Re: 回复");
        received.setBody(replyText);
        received.setReceivedAt(Instant.now());
        received.setDecision(decision);
        receivedEmailMapper.insert(received);

        log.info("💬 [Pipeline] candidateId={} status={} reply decision={} reasoning={}",
                candidateId, candidate.getStatus(), decision, assessment.reasoning());

        if (candidate.getStatus() == CandidateStatus.INVITE_SENT) {
            return candidateService.processEvent(candidateId, FsmEvent.INVITE_REPLIED,
                    Map.of("replyDecision", decision));
        }
        // OFFER_SENT：confirmed 映射为 accepted
        String offerDecision = "confirmed".equals(decision) ? "accepted" : "declined";
        return candidateService.processEvent(candidateId, FsmEvent.OFFER_REPLIED,
                Map.of("offerDecision", offerDecision));
    }

    /**
     * 面试官提交反馈：INTERVIEW_DONE → 评价汇总 → EVAL_PASS/EVAL_FAIL。
     *
     * <p>评价触发合规护栏（{@code DiscriminationGuardrail}）时<b>不自动决策</b>：
     * 候选人停在 INTERVIEWED 等面试官修订反馈后重新提交（合规违规宁可停，不可错）。
     */
    public CandidateContext completeInterview(Long candidateId, String feedback) {
        return completeInterview(candidateId, feedback, props.getDemo().isUseRealAgent());
    }

    public CandidateContext completeInterview(Long candidateId, String feedback, boolean useRealAgent) {
        MODE_OVERRIDE.set(useRealAgent);
        try {
            return doCompleteInterview(candidateId, feedback);
        } finally {
            MODE_OVERRIDE.remove();
        }
    }

    private CandidateContext doCompleteInterview(Long candidateId, String feedback) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate not found: " + candidateId);
        }
        if (candidate.getStatus() != CandidateStatus.INTERVIEW_SCHEDULED) {
            throw new IllegalStateException("候选人不在待面试状态: " + candidate.getStatus());
        }
        Interview interview = interviewMapper.selectList(new QueryWrapper<Interview>()
                .eq("candidate_id", candidateId).orderByDesc("id").last("LIMIT 1"))
                .stream().findFirst().orElseThrow(
                        () -> new IllegalStateException("找不到面试安排: " + candidateId));
        interview.setFeedback(feedback);
        interview.setStatus("DONE");
        interview.setUpdatedAt(Instant.now());
        interviewMapper.updateById(interview);

        candidateService.processEvent(candidateId, FsmEvent.INTERVIEW_DONE, Map.of());

        JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
        EvaluationAgent.EvaluationSummary summary =
                doEvaluateInterview(candidate, job == null ? "" : job.getTitle(), feedback);
        String outcome = "PASS".equalsIgnoreCase(summary.overall()) ? "PASS" : "FAIL";
        return candidateService.processEvent(candidateId,
                outcome.equals("PASS") ? FsmEvent.EVAL_PASS : FsmEvent.EVAL_FAIL,
                Map.of("evalOutcome", outcome));
    }

    /** HR 复核 ON_HOLD 候选人：approve → 进约面流程；否决 → 淘汰（必须给 reason）。 */
    public CandidateContext review(Long candidateId, boolean approve, String reason) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.ON_HOLD) {
            throw new IllegalStateException("候选人不在待定池: "
                    + (candidate == null ? "null" : candidate.getStatus()));
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("否决必须给出理由（拒信可解释性）");
        }
        return candidateService.processEvent(candidateId,
                approve ? FsmEvent.SCREEN_PASS : FsmEvent.SCREEN_FAIL,
                Map.of("humanApproved", approve));
    }

    /** HR 修正 AI 产出 → DPO 偏好样本。 */
    public void correction(Long candidateId, String prompt, String chosen, String rejected) {
        // DpoSampleWriter 由 controller 直调（见 CandidateController#correction），此处仅留编排位
        throw new UnsupportedOperationException("由 CandidateController 直接调用 DpoSampleWriter");
    }

    // ============================================================
    // FSM 迁移事件监听：确定性推进
    // ============================================================

    @EventListener
    public void onTransition(CandidateTransitionedEvent ev) {
        switch (ev.to()) {
            case SHORTLISTED -> generateInviteAndSend(ev.candidateId());
            case INTERVIEW_SCHEDULED -> bookSlotAndPrepare(ev.candidateId());
            case OFFER_SENT -> sendOfferLetter(ev.candidateId());
            default -> { /* 等待外部事件：INVITE_SENT/OFFER_SENT 等回复，终态结束 */ }
        }
    }

    /** SHORTLISTED → Bandit 选臂 → 邀请 Agent → 发送 → INVITE_SENT */
    private void generateInviteAndSend(Long candidateId) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.SHORTLISTED) return;
        log.info("✍️ [Pipeline] candidateId={} SHORTLISTED → generating invite", candidateId);

        JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
        String jobTitle = job == null ? "本职位" : job.getTitle();

        String armId = banditOptimizer.selectArm(candidateId,
                "candidate=" + candidate.getEmail() + " job=" + jobTitle, "");
        String[] parts = armId.split(":");
        String tone = parts.length > 0 ? parts[0] : "formal";
        String cta = parts.length > 1 ? parts[1] : "calendar-link";

        InviteContentAgent.InviteEmail invite = doGenerateInvite(candidate, jobTitle, tone, cta);
        candidate.setExtras("{\"arm_id\":\"" + armId + "\"}");
        candidateMapper.updateById(candidate);

        // 回填 arm_output（EvolutionScheduler 结算时读取）
        banditOutcomeMapper.selectList(new QueryWrapper<BanditOutcome>()
                        .eq("candidate_id", candidateId).isNull("processed_at")
                        .orderByDesc("id").last("LIMIT 1"))
                .stream().findFirst().ifPresent(o -> {
                    o.setArmOutput(invite.body());
                    banditOutcomeMapper.updateById(o);
                });

        int round = (candidate.getInviteRounds() == null ? 0 : candidate.getInviteRounds()) + 1;
        String messageId = mailGateway.send(candidate.getEmail(), invite.subject(), invite.body(),
                invite.language() == null ? "zh" : invite.language());
        insertSent(candidate, round, invite.subject(), invite.body(), messageId);

        candidateService.processEvent(candidateId, FsmEvent.INVITE_SENT, Map.of());
    }

    /** INTERVIEW_SCHEDULED → 日历占位 + 出题 + 确认函 */
    private void bookSlotAndPrepare(Long candidateId) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.INTERVIEW_SCHEDULED) return;
        log.info("📅 [Pipeline] candidateId={} INTERVIEW_SCHEDULED → booking slot", candidateId);

        JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
        String jobTitle = job == null ? "本职位" : job.getTitle();

        List<LocalDateTime> slots = calendarTool.availableSlots();
        if (slots.isEmpty()) {
            log.warn("⚠️ [Pipeline] candidateId={} no available slots, interview NOT booked", candidateId);
            return;
        }
        CalendarTool.Booking booking = calendarTool.book(slots.get(0), 90, candidateId);

        Interview interview = new Interview();
        interview.setCandidateId(candidateId);
        interview.setSlotStart(booking.start());
        interview.setDurationMinutes(booking.durationMinutes());
        interview.setMode("video");
        interview.setInterviewer(INTERVIEWER);
        interview.setBookingId(booking.bookingId());
        interview.setStatus("BOOKED");
        interview.setCreatedAt(Instant.now());
        interview.setUpdatedAt(Instant.now());

        InterviewQuestionAgent.QuestionSet qs = doGenerateQuestions(candidate, jobTitle);
        interview.setQuestions(qs == null ? null : toJson(qs.questions()));
        interviewMapper.insert(interview);

        // 确认函（事务邮件 round=0）
        String body = "您好 " + orDefault(candidate.getName(), "候选人") + "：\n\n"
                + "您应聘的「" + jobTitle + "」已安排视频面试：" + booking.start()
                + "，时长 " + booking.durationMinutes() + " 分钟。如需改期请直接回复本邮件。\n\n—— "
                + COMPANY + " 招聘团队";
        String messageId = mailGateway.send(candidate.getEmail(),
                "【" + COMPANY + "】面试安排确认：" + jobTitle, body, "zh");
        insertSent(candidate, 0, "【" + COMPANY + "】面试安排确认：" + jobTitle, body, messageId);
        log.info("📅 [Pipeline] candidateId={} booked {} + {} questions", candidateId,
                booking.bookingId(), qs == null ? 0 : qs.questions().size());
    }

    /** OFFER_SENT → offer 函（确定性模板：offer 是法律承诺文件，正文不走 LLM） */
    private void sendOfferLetter(Long candidateId) {
        CandidateContext candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getStatus() != CandidateStatus.OFFER_SENT) return;
        JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
        String jobTitle = job == null ? "本职位" : job.getTitle();

        String body = "您好 " + orDefault(candidate.getName(), "候选人") + "：\n\n"
                + "感谢您参与「" + jobTitle + "」的面试流程。我们很高兴地通知您：面试通过，"
                + "现正式向您发出 offer（薪酬与入职细节见附件，教学版从略）。"
                + "请在 5 个工作日内回复本邮件确认；逾期未答复我们将催办一次，再无答复视为放弃。\n\n—— "
                + COMPANY + " 招聘团队";
        String messageId = mailGateway.send(candidate.getEmail(),
                "【" + COMPANY + "】offer：欢迎加入 " + jobTitle, body, "zh");
        insertSent(candidate, 0, "【" + COMPANY + "】offer：欢迎加入 " + jobTitle, body, messageId);
        log.info("🎉 [Pipeline] candidateId={} offer letter sent", candidateId);
    }

    // ============================================================
    // Agent 调用（真实 / canned 兜底）
    // ============================================================

    private ResumeParserAgent.ParsedProfile doParse(CandidateContext candidate) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "ResumeParserAgent");
                ResumeParserAgent.ParsedProfile p = resumeParserAgent.parse(candidate.getResumeText());
                log.info("📄 [Pipeline] parse (real): name={} years={} skills={}",
                        p.name(), p.yearsOfExperience(), p.skills());
                return p;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] parse agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedParse(candidate.getResumeText());
    }

    private MatchEvaluatorAgent.MatchVerdict doEvaluate(CandidateContext candidate, JobPosition job) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "MatchEvaluatorAgent");
                MatchEvaluatorAgent.MatchVerdict v = matchEvaluatorAgent.evaluate(
                        job.getTitle(), job.getJdText(), candidate.getRedactedProfile());
                log.info("⚖️ [Pipeline] evaluate (real): score={} verdict={}", v.score(), v.verdict());
                return v;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] evaluate agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedEvaluate(candidate.getRedactedProfile(), job);
    }

    private InviteContentAgent.InviteEmail doGenerateInvite(CandidateContext candidate,
                                                            String jobTitle, String tone, String cta) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "InviteContentAgent");
                InviteContentAgent.InviteEmail e = inviteContentAgent.generate(
                        orDefault(candidate.getName(), "候选人"), jobTitle, tone, cta);
                log.info("✍️ [Pipeline] invite (real): arm={} subject=[{}]", tone + ":" + cta, e.subject());
                return e;
            } catch (Exception ex) {
                log.warn("⚠️ [Pipeline] invite agent failed, fallback to canned: {}", ex.getMessage());
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedInvite(candidate, jobTitle, tone, cta);
    }

    private ReplyClassifierAgent.ReplyAssessment doClassify(CandidateContext candidate, String replyText) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "ReplyClassifierAgent");
                ReplyClassifierAgent.ReplyAssessment a =
                        replyClassifierAgent.classify(orDefault(candidate.getName(), "候选人"), replyText);
                log.info("💡 [Pipeline] classify (real): {}", a);
                return a;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] classify agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedClassify(replyText);
    }

    private InterviewQuestionAgent.QuestionSet doGenerateQuestions(CandidateContext candidate, String jobTitle) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "InterviewQuestionAgent");
                JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
                InterviewQuestionAgent.QuestionSet q = interviewQuestionAgent.generate(jobTitle,
                        job == null ? "" : job.getJdText(), orDefault(candidate.getRedactedProfile(), "{}"));
                log.info("❓ [Pipeline] questions (real): {} 题", q.questions().size());
                return q;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] question agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedQuestions(candidate);
    }

    private EvaluationAgent.EvaluationSummary doEvaluateInterview(CandidateContext candidate,
                                                                  String jobTitle, String feedback) {
        if (realAgentEnabled()) {
            try {
                AgentCallContext.set(candidate.getId(), "EvaluationAgent");
                EvaluationAgent.EvaluationSummary s = evaluationAgent.summarize(
                        jobTitle, orDefault(candidate.getRedactedProfile(), "{}"), feedback);
                log.info("🗣 [Pipeline] evaluation (real): overall={} rating={}", s.overall(), s.rating());
                return s;
            } catch (Exception e) {
                // 合规护栏（歧视特征）触发会走到这里：不自动决策，停下来等人工
                log.warn("🚨 [Pipeline] evaluation blocked by guardrail, waiting for human revision: {}",
                        e.getMessage());
                throw new IllegalStateException("评价触发合规护栏，请修订反馈后重新提交: " + e.getMessage(), e);
            } finally {
                AgentCallContext.clear();
            }
        }
        return cannedEvaluation(feedback);
    }

    /** 生效模式：请求级覆盖优先，否则用配置 */
    private boolean realAgentEnabled() {
        Boolean override = MODE_OVERRIDE.get();
        return override != null ? override : props.getDemo().isUseRealAgent();
    }

    // ============================================================
    // canned 确定性兜底（无 LLM / 无 key 环境）
    // ============================================================

    private static final Pattern EMAIL_P = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_P = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern YEARS_P = Pattern.compile("(\\d{1,2})\\s*年");
    private static final List<String> SKILL_DICT = List.of(
            "Java", "Spring", "Spring Boot", "MySQL", "Redis", "LangChain4j", "RAG",
            "Python", "PyTorch", "React", "Vue", "Kubernetes", "Docker", "Kafka",
            "MyBatis", "JVM", "向量", "微服务");

    /** 规则版解析：正则 + 词表，LLM 不存在也产出确定结果 */
    private ResumeParserAgent.ParsedProfile cannedParse(String resumeText) {
        String text = resumeText == null ? "" : resumeText;
        String name = null;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && t.length() <= 12 && !t.contains("@") && !t.contains("：")
                    && !t.contains(":") && !EMAIL_P.matcher(t).find()) {
                name = t;
                break;
            }
        }
        Matcher emailM = EMAIL_P.matcher(text);
        Matcher phoneM = PHONE_P.matcher(text);
        Matcher yearsM = YEARS_P.matcher(text);
        List<String> skills = new ArrayList<>();
        for (String s : SKILL_DICT) {
            if (text.contains(s) && !skills.contains(s)) skills.add(s);
        }
        String education = text.contains("硕士") ? "硕士"
                : text.contains("本科") ? "本科" : text.contains("博士") ? "博士" : null;
        List<String> projects = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if ((t.startsWith("- ") || t.startsWith("• ")) && projects.size() < 3) {
                projects.add(t.substring(2).trim());
            }
        }
        return new ResumeParserAgent.ParsedProfile(
                name,
                emailM.find() ? emailM.group() : null,
                phoneM.find() ? phoneM.group() : null,
                yearsM.find() ? Integer.parseInt(yearsM.group(1)) : null,
                skills, education, projects, "canned: 正则+词表解析");
    }

    /** 规则版评估：技能交集 + 年限加权，阈值 70/40 分档 */
    private MatchEvaluatorAgent.MatchVerdict cannedEvaluate(String redactedProfileJson, JobPosition job) {
        List<String> required = parseSkills(job.getRequiredSkills());
        List<String> owned = parseSkills(redactedProfileJson);
        List<String> matched = new ArrayList<>(owned);
        matched.retainAll(required);
        int years = extractYears(redactedProfileJson);
        int score = Math.min(100, matched.size() * 20 + Math.min(years, 5) * 5);
        List<String> reasons = new ArrayList<>();
        reasons.add("命中必备技能 " + matched.size() + "/" + required.size()
                + (matched.isEmpty() ? "" : "：" + String.join("、", matched)));
        reasons.add("工作年限 " + years + " 年");
        if (!required.isEmpty()) {
            List<String> missing = new ArrayList<>(required);
            missing.removeAll(matched);
            if (!missing.isEmpty()) reasons.add("缺少技能：" + String.join("、", missing));
        }
        String verdict = score >= 70 ? "SHORTLIST" : score >= 40 ? "REVIEW" : "REJECT";
        return new MatchEvaluatorAgent.MatchVerdict(score, verdict, reasons);
    }

    /** 模板邀请（按 Bandit 选臂 → CannedInviteTemplates 6 模板之一） */
    private InviteContentAgent.InviteEmail cannedInvite(CandidateContext candidate,
                                                        String jobTitle, String tone, String cta) {
        CannedInviteTemplates.Template t = CannedInviteTemplates.render(
                CannedInviteTemplates.forArm(tone + ":" + cta + ":email"),
                orDefault(candidate.getName(), "候选人"), jobTitle, COMPANY);
        return new InviteContentAgent.InviteEmail(t.subject(), t.body(), "zh");
    }

    /** 关键词规则版回复分类 */
    private ReplyClassifierAgent.ReplyAssessment cannedClassify(String replyText) {
        String t = replyText == null ? "" : replyText.toLowerCase(Locale.ROOT);
        if (t.contains("放弃") || t.contains("不考虑") || t.contains("拒绝") || t.contains("退出")
                || t.contains("decline") || t.contains("withdraw")) {
            return new ReplyClassifierAgent.ReplyAssessment("declined", "canned: 明确拒绝");
        }
        return new ReplyClassifierAgent.ReplyAssessment("confirmed",
                "canned: 未明确拒绝，按确认处理并电话跟进");
    }

    /** 规则版出题：核心技能 3 题 + 项目 1 题 + 盲区 1 题 */
    private InterviewQuestionAgent.QuestionSet cannedQuestions(CandidateContext candidate) {
        List<String> owned = parseSkills(candidate.getRedactedProfile());
        JobPosition job = jobPositionMapper.selectById(candidate.getJobId());
        List<String> required = job == null ? List.of() : parseSkills(job.getRequiredSkills());
        List<String> questions = new ArrayList<>();
        for (int i = 0; i < Math.min(3, owned.size()); i++) {
            questions.add("请结合你使用 " + owned.get(i) + " 的经历，讲一个最有挑战的问题及解决过程。");
        }
        questions.add("挑一段简历中的项目经历，说明你在其中的具体职责与产出。");
        List<String> blind = new ArrayList<>(required);
        blind.removeAll(owned);
        questions.add(blind.isEmpty()
                ? "谈谈你对本职位技术栈的理解与学习计划。"
                : "我们团队在用 " + blind.get(0) + "，你了解多少？说一下你的理解。");
        return new InterviewQuestionAgent.QuestionSet(questions, "canned: 技能深挖+盲区探测");
    }

    /** 关键词规则版评价汇总 */
    private EvaluationAgent.EvaluationSummary cannedEvaluation(String feedback) {
        String t = feedback == null ? "" : feedback.toLowerCase(Locale.ROOT);
        boolean pass = t.contains("通过") || t.contains("pass") || t.contains("不错")
                || t.contains("strong") || t.contains("扎实");
        List<String> strengths = List.of("canned: 从反馈提取的亮点（教学版逐字引用略）");
        List<String> concerns = List.of("canned: 从反馈提取的疑虑（教学版逐字引用略）");
        return new EvaluationAgent.EvaluationSummary(
                pass ? "PASS" : "FAIL", pass ? 4 : 2, strengths, concerns,
                "canned: 规则版汇总（生产由 LLM 汇总并过合规护栏）");
    }

    // ============================================================
    // 私有辅助
    // ============================================================

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseSkills(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var node = objectMapper.readTree(json);
            // 兼容三种形态：数组本身 / 含 skills 键的对象 / 手写逗号串
            if (node.isArray()) {
                return objectMapper.convertValue(node, List.class);
            }
            if (node.has("skills")) {
                return objectMapper.convertValue(node.get("skills"), List.class);
            }
            return List.of();
        } catch (Exception e) {
            // 非 JSON（旧数据/手写逗号串）：按分隔符切
            return List.of(json.replace("[", "").replace("]", "").replace("\"", "")
                    .split("[,、，]"));
        }
    }

    private int extractYears(String profileJson) {
        String json = profileJson == null ? "" : profileJson;
        // 优先取结构化字段（"yearsOfExperience": 5），canned/真实两种画像都认
        Matcher field = Pattern.compile("\"yearsOfExperience\"\\s*:\\s*(\\d+)").matcher(json);
        if (field.find()) {
            return Integer.parseInt(field.group(1));
        }
        Matcher m = YEARS_P.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String normalizeVerdict(String verdict) {
        if (verdict == null) return "REVIEW";
        return switch (verdict.trim().toUpperCase(Locale.ROOT)) {
            case "SHORTLIST", "SHORTLISTED" -> "SHORTLIST";
            case "REJECT", "REJECTED" -> "REJECT";
            default -> "REVIEW";
        };
    }

    private String normalizeDecision(String decision) {
        if (decision == null) return "confirmed";
        String d = decision.trim().toLowerCase(Locale.ROOT);
        return d.equals("declined") ? "declined" : "confirmed";
    }

    private String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private void insertSent(CandidateContext candidate, int round, String subject, String body, String messageId) {
        SentEmail email = new SentEmail();
        email.setCandidateId(candidate.getId());
        email.setRoundNo(round);
        email.setFromEmail(FROM_EMAIL);
        email.setToEmail(candidate.getEmail());
        email.setSubject(subject);
        email.setBody(body);
        email.setLanguage("zh");
        email.setStatus("sent");
        email.setSmtpMessageId(messageId);
        email.setSentAt(Instant.now());
        sentEmailMapper.insert(email);
    }
}
