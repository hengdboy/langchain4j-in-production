package com.example.sdr.service;

import com.example.sdr.agent.InquiryAssessorAgent;
import com.example.sdr.api.CreateLeadRequest;
import com.example.sdr.config.SdrProperties;
import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadEvent;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.mapper.LeadEventMapper;
import com.example.sdr.mapper.LeadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ch24 demo：注入一条询盘 → 6 阶段流转（v0.26 起基于事件驱动编排）。
 *
 * <h2>v0.26 设计</h2>
 * <ul>
 *   <li><b>阶段一</b>：{@link InquiryAssessorAgent} 评估询盘（真实/canned），低意向直接 POOL</li>
 *   <li><b>阶段二~四</b>：交给 {@link LeadPipelineService#ingest} —— 背调→内容→发信自动推进到 STEP1_SENT</li>
 *   <li><b>阶段五</b>：模拟客户回信 → {@link LeadPipelineService#onReply} 判级 → INTENT_x</li>
 *   <li><b>阶段五后</b>：A/B 级自动销售接管 → HANDOFF（B 需 salesAccepted=true）</li>
 * </ul>
 * <p>timeline 从 {@code lead_event} 审计流水重建，保证与真实链路一致。
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoService {

    /** demo 模拟客户回信（高意向） */
    static final String SIMULATED_REPLY =
            "We are interested. Please send a quote for 500 IoT gateways and arrange a call with our procurement manager.";

    private final LeadMapper leadMapper;
    private final LeadEventMapper leadEventMapper;
    private final LeadService leadService;
    private final LeadPipelineService pipeline;
    private final SdrProperties props;
    private final InquiryAssessorAgent inquiryAssessorAgent;

    /**
     * demo 运行结果。
     *
     * @param lead     最终 Lead（含状态）
     * @param timeline 状态流转时间线（文本）
     */
    public record DemoResult(LeadContext lead, List<String> timeline) {}

    /**
     * 跑一遍 demo。
     *
     * @param email         客户邮箱
     * @param company       公司名
     * @param country       国家代码
     * @param inquiryBody   询盘正文（阶段一评估用，可空）
     * @param useRealAgent  是否真实调 Agent（null → 取 sdr.demo.use-real-agent 配置）
     */
    public DemoResult run(String email, String company, String country,
                          String inquiryBody, Boolean useRealAgent) {
        boolean real = useRealAgent != null ? useRealAgent : props.getDemo().isUseRealAgent();
        List<String> timeline = new ArrayList<>();

        // ============ 阶段一：询盘评估（真实 Agent 或 canned） ============
        InquiryAssessorAgent.InquiryAssessment assessment = assess(inquiryBody, company, real);
        timeline.add("评估：intentScore=" + (assessment.intentScore() == null ? "n/a" : assessment.intentScore())
                + " company=" + assessment.companyName()
                + " hasKP=" + assessment.hasDecisionMaker()
                + (real ? "（真实 Agent）" : "（canned）"));

        boolean highIntent = assessment.intentScore() != null
                && assessment.intentScore() >= 0.5
                && assessment.hasDecisionMaker();

        if (!highIntent) {
            // ============ 阶段一：低意向 → 直接 POOL（不经背调） ============
            LeadContext lead = newLead(email, company, country);
            leadMapper.insert(lead);
            leadService.processEvent(lead.getId(), FsmEvent.QUALIFY_FAIL, Map.of("smtpVerified", false));
            timeline.add("POOL：低意向/无决策人，沉淀公海");
            return new DemoResult(leadMapper.selectById(lead.getId()), timeline);
        }

        // ============ 阶段二~四：事件驱动自动推进（背调→内容→发信） ============
        long[] cursor = {0L};
        LeadContext afterIngest = pipeline.ingest(
                new CreateLeadRequest(email, company, country, null, null, null), real);
        appendTimeline(timeline, afterIngest.getId(), cursor);

        // 背调失败 → POOL 结束
        if (afterIngest.getStatus() == LeadStatus.POOL) {
            timeline.add("POOL：背调失败（smtp/kp 未通过），沉淀公海");
            return new DemoResult(afterIngest, timeline);
        }

        // ============ 阶段五：模拟客户回信 → 判级 → INTENT_x ============
        LeadContext afterReply = pipeline.onReply(afterIngest.getId(), SIMULATED_REPLY, real);
        appendTimeline(timeline, afterReply.getId(), cursor);

        // ============ 阶段五：A/B 级销售接管 → HANDOFF ============
        if (afterReply.getStatus() == LeadStatus.INTENT_A
                || afterReply.getStatus() == LeadStatus.INTENT_B) {
            boolean salesAccepted = afterReply.getStatus() == LeadStatus.INTENT_A;
            LeadContext afterHandover = leadService.processEvent(afterReply.getId(),
                    FsmEvent.SALES_HANDOVER, Map.of("salesAccepted", salesAccepted));
            appendTimeline(timeline, afterHandover.getId(), cursor);
            return new DemoResult(afterHandover, timeline);
        }

        return new DemoResult(afterReply, timeline);
    }

    // ===================== 私有辅助 =====================

    private LeadContext newLead(String email, String company, String country) {
        LeadContext lead = new LeadContext();
        lead.setCustomerEmail(email);
        lead.setCompanyName(company);
        lead.setCountryCode(country);
        lead.setStatus(LeadStatus.RAW);
        lead.setSentRounds(0);
        lead.setCreatedAt(java.time.Instant.now());
        lead.setUpdatedAt(java.time.Instant.now());
        return lead;
    }

    /** 只追加自 cursor 之后新增的 lead_event（避免重复累加） */
    private void appendTimeline(List<String> timeline, Long leadId, long[] cursor) {
        List<LeadEvent> events = leadEventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LeadEvent>()
                        .eq("lead_id", leadId).gt("id", cursor[0]).orderByAsc("id"));
        for (LeadEvent e : events) {
            String line = humanize(e.getEvent(), e.getToStatus());
            if (line != null) timeline.add(line);
            if (e.getId() != null && e.getId() > cursor[0]) cursor[0] = e.getId();
        }
    }

    private String humanize(String event, String toStatus) {
        if (toStatus == null) return null;
        return switch (toStatus) {
            case "QUALIFIED" -> "QUALIFIED：背调通过";
            case "READY" -> "READY：开发信就绪";
            case "STEP1_SENT" -> "STEP1_SENT：第 1 轮触达中（72h 静默期监听已调度）";
            case "POOL" -> "POOL：沉淀公海";
            case "INTENT_A" -> "INTENT_A：客户回信高意向（72h 定时器已取消）";
            case "INTENT_B" -> "INTENT_B：客户回信中意向（72h 定时器已取消）";
            case "INTENT_C" -> "INTENT_C：客户回信低意向";
            case "INTENT_D" -> "INTENT_D：客户回信无意向";
            case "HANDOFF" -> "HANDOFF：销售接管（终态）";
            default -> null;
        };
    }

    /** 询盘评估：useRealAgent + inquiryBody → 真实 Agent；否则 canned（失败兜底） */
    private InquiryAssessorAgent.InquiryAssessment assess(String inquiryBody, String company, boolean real) {
        if (real && inquiryBody != null && !inquiryBody.isBlank()) {
            try {
                InquiryAssessorAgent.InquiryAssessment a = inquiryAssessorAgent.assessInquiry(inquiryBody);
                log.info("🔍 [Demo] real agent assessment: {}", a);
                return a;
            } catch (Exception e) {
                log.warn("⚠️ [Demo] real agent failed, fallback to canned: {}", e.getMessage());
            }
        }
        return new InquiryAssessorAgent.InquiryAssessment(
                0.85, company, "smart home", true, "canned demo assessment");
    }
}