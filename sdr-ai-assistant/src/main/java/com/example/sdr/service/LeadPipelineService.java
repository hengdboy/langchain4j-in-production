package com.example.sdr.service;

import com.example.sdr.agent.IntentClassifierAgent;
import com.example.sdr.agent.LeadResearchAgent;
import com.example.sdr.agent.OutreachContentAgent;
import com.example.sdr.api.CreateLeadRequest;
import com.example.sdr.config.SdrProperties;
import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.core.LeadTransitionedEvent;
import com.example.sdr.entity.ReceivedEmail;
import com.example.sdr.entity.SentEmail;
import com.example.sdr.mapper.LeadMapper;
import com.example.sdr.mapper.ReceivedEmailMapper;
import com.example.sdr.mapper.SentEmailMapper;
import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.MailGateway;
import com.example.sdr.tools.SmtpVerifyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ch24 事件驱动编排：SDR 业务流水线（v0.26 核心）。
 *
 * <h2>设计：确定性 FSM + LLM 叶子</h2>
 * <p><b>编排不是 LLM 自主决定</b>，而是由 FSM 状态迁移驱动：
 * <ol>
 *   <li>外部事件（新建线索 / 客户回信 / 超时）进入本服务</li>
 *   <li>{@link LeadService#processEvent} 是唯一状态机入口，accepted 转换后发布
 *       {@link LeadTransitionedEvent}</li>
 *   <li>本服务监听迁移事件，按<b>新状态</b>确定性触发对应 Agent：
 *       QUALIFIED → 内容 Agent；READY → 邮件网关；其他 → 等待</li>
 *   <li>Agent 输出结构化结果，作为下一 FSM 事件的 overrides 回喂</li>
 * </ol>
 *
 * <pre>
 *   ingest()                         onReply()
 *     │                                 │
 *     ▼                                 ▼
 *   LeadResearchAgent → QUALIFY_OK ──▶ QUALIFIED ──▶ OutreachContentAgent → CONTENT_READY
 *                       QUALIFY_FAIL ─▶ POOL        ──▶ READY ──▶ MailGateway → EMAIL_SENT
 *                                                       └─▶ STEP1_SENT（等待回信/超时）
 * </pre>
 *
 * <h2>真实 vs canned</h2>
 * <p>每个 Agent 调用先看 {@code sdr.demo.use-real-agent}：
 * <ul>
 *   <li>true → 调真实 LLM Agent，失败兜底 canned</li>
 *   <li>false → 直接走确定性规则（无 LLM，无 key 环境可跑）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadPipelineService {

    /** 模拟发信账号（生产替换为账户池，见 SdrProperties.Tools.smtpPoolSize） */
    private static final String FROM_EMAIL = "sdr@postbox.workbuddy.dev";

    /**
     * 请求级 canned/真实覆盖开关。
     *
     * <p>{@code ingest/onReply(..., boolean useRealAgent)} 显式传参时写入，
     * 保证整条事件链（含 @EventListener 触发的后续 Agent）用同一个模式；
     * 未显式传参时用配置 {@code sdr.demo.use-real-agent}。
     * 必须 try/finally 清理，防止线程复用串模式。
     */
    private static final ThreadLocal<Boolean> MODE_OVERRIDE = new ThreadLocal<>();

    private final LeadMapper leadMapper;
    private final SentEmailMapper sentEmailMapper;
    private final ReceivedEmailMapper receivedEmailMapper;
    private final LeadService leadService;
    private final SdrProperties props;
    private final ObjectMapper objectMapper;
    private final com.example.sdr.evolution.BanditOptimizer banditOptimizer;
    private final com.example.sdr.mapper.BanditOutcomeMapper banditOutcomeMapper;

    private final LeadResearchAgent researchAgent;
    private final OutreachContentAgent contentAgent;
    private final IntentClassifierAgent classifierAgent;
    private final MailGateway mailGateway;
    private final CustomsLookupTool customsLookupTool;
    private final SmtpVerifyTool smtpVerifyTool;
    private final KpFinderTool kpFinderTool;

    // ============================================================
    // 外部事件入口
    // ============================================================

    /**
     * 新建线索并自动推进：背调 → （通过则）生成内容 → 发信 → STEP1_SENT；失败 → POOL。
     */
    public LeadContext ingest(CreateLeadRequest req) {
        return ingest(req, props.getDemo().isUseRealAgent());
    }

    public LeadContext ingest(CreateLeadRequest req, boolean useRealAgent) {
        MODE_OVERRIDE.set(useRealAgent);
        try {
            return doIngest(req);
        } finally {
            MODE_OVERRIDE.remove();
        }
    }

    private LeadContext doIngest(CreateLeadRequest req) {
        LeadContext lead = new LeadContext();
        lead.setCustomerEmail(req.email());
        lead.setCompanyName(req.company());
        lead.setCountryCode(req.country());
        lead.setKeyPerson(req.keyPerson());
        lead.setKeyPersonEmail(req.keyPersonEmail());
        lead.setSmtpVerified(Boolean.TRUE.equals(req.smtpVerified()));
        lead.setStatus(LeadStatus.RAW);
        lead.setSentRounds(0);
        lead.setCreatedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        leadMapper.insert(lead);

        log.info("🚀 [Pipeline] ingest leadId={} company={} country={} realAgent={}",
                lead.getId(), lead.getCompanyName(), lead.getCountryCode(), realAgentEnabled());

        // 阶段二：深度背调
        LeadResearchAgent.ResearchReport research = doResearch(lead);
        applyResearch(lead, research);

        if (research.passed()) {
            leadService.processEvent(lead.getId(), FsmEvent.QUALIFY_OK,
                    Map.of("smtpVerified", research.smtpVerified()));
            // 后续 QUALIFIED→READY→STEP1_SENT 由 LeadTransitionedEvent 监听器自动推进
        } else {
            leadService.processEvent(lead.getId(), FsmEvent.QUALIFY_FAIL,
                    Map.of("smtpVerified", false));
        }
        return leadMapper.selectById(lead.getId());
    }

    /**
     * 客户回信：判级 → EMAIL_REPLIED(intentGrade=A/B/C/D)。
     */
    public LeadContext onReply(Long leadId, String replyText) {
        return onReply(leadId, replyText, props.getDemo().isUseRealAgent());
    }

    public LeadContext onReply(Long leadId, String replyText, boolean useRealAgent) {
        MODE_OVERRIDE.set(useRealAgent);
        try {
            return doReply(leadId, replyText);
        } finally {
            MODE_OVERRIDE.remove();
        }
    }

    private LeadContext doReply(Long leadId, String replyText) {
        LeadContext lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw new IllegalArgumentException("Lead not found: " + leadId);
        }
        if (lead.getStatus() != LeadStatus.STEP1_SENT) {
            log.info("💬 [Pipeline] leadId={} status={} not awaiting reply, ignore", leadId, lead.getStatus());
            return lead;
        }

        IntentClassifierAgent.IntentAssessment intent = doClassify(lead, replyText);
        String grade = normalizeGrade(intent.grade());

        // 回信落库（含判级结果）
        ReceivedEmail received = new ReceivedEmail();
        received.setLeadId(leadId);
        received.setFromEmail(lead.getCustomerEmail());
        received.setSubject("Re: " + (lead.getKeyPerson() == null ? "partnership" : lead.getKeyPerson()));
        received.setBody(replyText);
        received.setReceivedAt(Instant.now());
        received.setIntentGrade(grade);
        receivedEmailMapper.insert(received);

        log.info("💬 [Pipeline] leadId={} reply graded={} reasoning={}",
                leadId, grade, intent.reasoning());

        return leadService.processEvent(leadId, FsmEvent.EMAIL_REPLIED, Map.of("intentGrade", grade));
    }

    // ============================================================
    // FSM 迁移事件监听：确定性推进
    // ============================================================

    /**
     * 状态迁移后自动推进下一个阶段（事件驱动核心）。
     */
    @EventListener
    public void onTransition(LeadTransitionedEvent ev) {
        switch (ev.to()) {
            case QUALIFIED -> generateContentAndReady(ev.leadId());
            case READY -> sendFirstEmail(ev.leadId());
            default -> { /* STEP1_SENT / POOL / DORMANT / HANDOFF：等待外部事件 */ }
        }
    }

    /** QUALIFIED → 内容 Agent 生成开发信 → CONTENT_READY（→ READY） */
    private void generateContentAndReady(Long leadId) {
        LeadContext lead = leadMapper.selectById(leadId);
        if (lead == null || lead.getStatus() != LeadStatus.QUALIFIED) return;
        log.info("✍️ [Pipeline] leadId={} QUALIFIED → generating outreach content", leadId);

        // v0.28 Bandit 选 arm_id（canned/真实模式都生效；真实模式 armId 也作为 prompt 输入）
        String armId = banditOptimizer.selectArm(leadId,
                "company=" + lead.getCompanyName() + " kp=" + lead.getKeyPerson()
                        + " imports=" + lead.getMatchedProductIds(),
                "");

        OutreachContentAgent.OutreachEmail email = doGenerateContent(lead, armId);
        lead.setSelectedLanguage(email.language());
        lead.setExtras("{\"arm_id\":\"" + armId + "\"}");
        leadMapper.updateById(lead);

        // 回填 arm_output（EvolutionScheduler 结算时读取此字段写 DPO）
        banditOutcomeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.example.sdr.entity.BanditOutcome>()
                        .eq("lead_id", leadId).isNull("processed_at")
                        .orderByDesc("id").last("LIMIT 1"))
                .stream().findFirst().ifPresent(o -> {
                    o.setArmOutput(email.body());
                    banditOutcomeMapper.updateById(o);
                });

        // 开发信先落库（round=0 草案），READY 后转正式发送
        SentEmail draft = new SentEmail();
        draft.setLeadId(leadId);
        draft.setRoundNo(1);
        draft.setFromEmail(FROM_EMAIL);
        draft.setToEmail(lead.getCustomerEmail());
        draft.setSubject(email.subject());
        draft.setBody(email.body());
        draft.setLanguage(email.language());
        draft.setStatus("ready");
        draft.setSentAt(Instant.now());
        sentEmailMapper.insert(draft);

        leadService.processEvent(leadId, FsmEvent.CONTENT_READY, Map.of());
    }

    /** READY → 邮件网关发送首发 → EMAIL_SENT（→ STEP1_SENT） */
    private void sendFirstEmail(Long leadId) {
        LeadContext lead = leadMapper.selectById(leadId);
        if (lead == null || lead.getStatus() != LeadStatus.READY) return;
        log.info("📤 [Pipeline] leadId={} READY → sending first email", leadId);

        SentEmail email = sentEmailMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SentEmail>()
                        .eq("lead_id", leadId)
                        .orderByAsc("id")
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (email == null) {
            log.warn("⚠️ [Pipeline] leadId={} READY but no drafted email, skip", leadId);
            return;
        }

        String messageId = mailGateway.send(email.getToEmail(), email.getSubject(),
                email.getBody(), email.getLanguage());
        email.setStatus("sent");
        email.setSmtpMessageId(messageId);
        email.setSentAt(Instant.now());
        sentEmailMapper.updateById(email);

        leadService.processEvent(leadId, FsmEvent.EMAIL_SENT, Map.of());
    }

    // ============================================================
    // Agent 调用（真实 / canned 兜底）
    // ============================================================

    private LeadResearchAgent.ResearchReport doResearch(LeadContext lead) {
        if (realAgentEnabled()) {
            try {
                com.example.sdr.observability.AgentCallContext.set(lead.getId(), "LeadResearchAgent");
                LeadResearchAgent.ResearchReport r = researchAgent.research(
                        lead.getCompanyName(), lead.getCountryCode(), lead.getCustomerEmail());
                log.info("🔎 [Pipeline] research (real): {}", r);
                return r;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] research agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                com.example.sdr.observability.AgentCallContext.clear();
            }
        }
        return cannedResearch(lead);
    }

    private OutreachContentAgent.OutreachEmail doGenerateContent(LeadContext lead, String armId) {
        if (realAgentEnabled()) {
            try {
                String importRecords = lead.getMatchedProductIds() == null
                        ? "smart home devices" : lead.getMatchedProductIds();
                // 真实模式：prompt 告诉 LLM 选哪个 arm_id 的风格（v0.28 Bandit 选臂）
                String productKeywords = "smart home & IoT solutions (tone=" + armId.split(":")[0]
                        + ", cta=" + armId.split(":")[1] + ")";
                com.example.sdr.observability.AgentCallContext.set(lead.getId(), "OutreachContentAgent");
                OutreachContentAgent.OutreachEmail e = contentAgent.generate(
                        lead.getCompanyName(), lead.getCountryCode(),
                        languageOf(lead.getCountryCode()),
                        lead.getKeyPerson() == null ? "there" : lead.getKeyPerson(),
                        importRecords,
                        productKeywords);
                log.info("✍️ [Pipeline] content (real): arm={} subject=[{}] lang={}", armId, e.subject(), e.language());
                return e;
            } catch (Exception ex) {
                log.warn("⚠️ [Pipeline] content agent failed, fallback to canned: {}", ex.getMessage());
            } finally {
                com.example.sdr.observability.AgentCallContext.clear();
            }
        }
        return cannedContent(lead, armId);
    }

    private IntentClassifierAgent.IntentAssessment doClassify(LeadContext lead, String replyText) {
        if (realAgentEnabled()) {
            try {
                com.example.sdr.observability.AgentCallContext.set(lead.getId(), "IntentClassifierAgent");
                IntentClassifierAgent.IntentAssessment a = classifierAgent.classify(
                        replyText, lead.getCompanyName(), "smart home & IoT solutions");
                log.info("💡 [Pipeline] classify (real): {}", a);
                return a;
            } catch (Exception e) {
                log.warn("⚠️ [Pipeline] classifier agent failed, fallback to canned: {}", e.getMessage());
            } finally {
                com.example.sdr.observability.AgentCallContext.clear();
            }
        }
        return cannedClassify(replyText);
    }

    /** 生效模式：请求级覆盖优先，否则用配置 */
    private boolean realAgentEnabled() {
        Boolean override = MODE_OVERRIDE.get();
        return override != null ? override : props.getDemo().isUseRealAgent();
    }

    // ============================================================
    // canned 确定性兜底（无 LLM / 无 key 环境）
    // ============================================================

    /** 规则版背调：直接组合 mock 工具，LLM 不存在也产出确定结果 */
    private LeadResearchAgent.ResearchReport cannedResearch(LeadContext lead) {
        SmtpVerifyTool.SmtpStatus smtp = smtpVerifyTool.verify(lead.getCustomerEmail());
        List<String> imports = customsLookupTool.lookup(lead.getCompanyName(), lead.getCountryCode());
        KpFinderTool.KpInfo kp = kpFinderTool.find(lead.getCompanyName());
        boolean passed = smtp.deliverable() && kp.email() != null;
        return new LeadResearchAgent.ResearchReport(
                passed, 0.8, smtp.deliverable(), kp.email() != null,
                kp.name(), kp.email(), imports,
                "canned: smtp=" + smtp.status() + " kp=" + (kp.email() != null ? "found" : "none")
                        + " imports=" + imports.size());
    }

    /** 模板开发信（v0.28 按 Bandit 选 arm_id → CannedContentTemplates 6 模板之一） */
    private OutreachContentAgent.OutreachEmail cannedContent(LeadContext lead, String armId) {
        String lang = languageOf(lead.getCountryCode());
        String kp = lead.getKeyPerson() == null ? "there" : lead.getKeyPerson();
        String imports = lead.getMatchedProductIds() == null
                ? "smart home devices" : lead.getMatchedProductIds();
        com.example.sdr.evolution.CannedContentTemplates.Template t =
                com.example.sdr.evolution.CannedContentTemplates.render(
                        com.example.sdr.evolution.CannedContentTemplates.forArm(armId),
                        lead.getCompanyName(), kp, imports);
        return new OutreachContentAgent.OutreachEmail(t.subject(), t.body(), lang);
    }

    /** 关键词规则版意向分级 */
    private IntentClassifierAgent.IntentAssessment cannedClassify(String replyText) {
        String t = replyText == null ? "" : replyText.toLowerCase(Locale.ROOT);
        if (t.contains("quote") || t.contains("price") || t.contains("order")
                || t.contains("call") || t.contains("数量") || t.contains("报价")) {
            return new IntentClassifierAgent.IntentAssessment("A", "canned: 明确询价/约电话", true);
        }
        if (t.contains("interested") || t.contains("info") || t.contains("catalog")
                || t.contains("感兴趣") || t.contains("资料")) {
            return new IntentClassifierAgent.IntentAssessment("B", "canned: 有兴趣待跟进", false);
        }
        if (t.contains("thanks") || t.contains("not now") || t.contains("later")
                || t.contains("谢谢") || t.contains("暂时")) {
            return new IntentClassifierAgent.IntentAssessment("C", "canned: 低意向", false);
        }
        return new IntentClassifierAgent.IntentAssessment("D", "canned: 无意向/退订", false);
    }

    // ============================================================
    // 私有辅助
    // ============================================================

    /** 背调结果回填 lead 字段（keyPerson / smtpVerified / matchedProductIds） */
    private void applyResearch(LeadContext lead, LeadResearchAgent.ResearchReport r) {
        lead.setSmtpVerified(r.smtpVerified());
        if (r.kpName() != null) lead.setKeyPerson(r.kpName());
        if (r.kpEmail() != null) lead.setKeyPersonEmail(r.kpEmail());
        if (r.importRecords() != null && !r.importRecords().isEmpty()) {
            try {
                lead.setMatchedProductIds(objectMapper.writeValueAsString(r.importRecords()));
            } catch (Exception e) {
                lead.setMatchedProductIds(String.join(",", r.importRecords()));
            }
        }
        lead.setUpdatedAt(Instant.now());
        leadMapper.updateById(lead);
    }

    /** 国家 → 语种映射（教学简化版） */
    private String languageOf(String countryCode) {
        if (countryCode == null) return "en";
        return switch (countryCode.toUpperCase(Locale.ROOT)) {
            case "DE" -> "de";
            case "FR" -> "fr";
            case "BR", "ES", "MX" -> "es";
            case "CN" -> "zh";
            case "JP" -> "ja";
            case "KR" -> "ko";
            default -> "en";
        };
    }

    /** 把 LLM 输出的 grade 规范化为 A-D（非法 → D） */
    private String normalizeGrade(String grade) {
        if (grade == null) return "D";
        String g = grade.trim().toUpperCase(Locale.ROOT);
        if (g.matches("[A-D]")) return g;
        return "D";
    }
}