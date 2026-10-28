package com.example.sdr;

import com.example.sdr.api.CreateLeadRequest;
import com.example.sdr.core.DelayedEventQueue;
import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.entity.SentEmail;
import com.example.sdr.mapper.LeadEventMapper;
import com.example.sdr.mapper.LeadMapper;
import com.example.sdr.mapper.ReceivedEmailMapper;
import com.example.sdr.mapper.SentEmailMapper;
import com.example.sdr.service.LeadPipelineService;
import com.example.sdr.service.LeadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ch24 FSM 事件驱动编排集成测试（v0.26）。
 *
 * <p>前置：MySQL（sdrdb）+ Redis 已启动（brew / docker）。测试用
 * {@code sdr.demo.use-real-agent=false} 走确定性 canned 流水线，不依赖 LLM。
 *
 * <h2>覆盖</h2>
 * <ul>
 *   <li>事件驱动：ingest 自动推进 RAW→背调→内容→发信→STEP1_SENT（3 条 lead_event + 1 条 sent_email）</li>
 *   <li>背调失败：smtp 不可达 → QUALIFY_FAIL → POOL</li>
 *   <li>回信判级：onReply → INTENT_A（高意向）/ INTENT_D（退订）→ 销售接管 HANDOFF</li>
 *   <li>追发链路：3 轮 TIMEOUT_72H → DORMANT + RE_CRAWL_TRIGGER 调度</li>
 *   <li>Guard 拒绝：EMAIL_REPLIED 无 intentGrade / INTENT_B→HANDOFF 无 salesAccepted</li>
 *   <li>API：/api/leads（自动推进）、/api/leads/{id}/reply、/api/demo/run</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sdr.demo.use-real-agent=false")
@DisplayName("SDR 事件驱动编排集成测试")
class SdrIntegrationTest {

    @Autowired LeadMapper leadMapper;
    @Autowired LeadEventMapper leadEventMapper;
    @Autowired SentEmailMapper sentEmailMapper;
    @Autowired ReceivedEmailMapper receivedEmailMapper;
    @Autowired LeadService leadService;
    @Autowired LeadPipelineService pipeline;
    @Autowired DelayedEventQueue delayedQueue;
    @Autowired MockMvc mockMvc;

    private static final AtomicLong SEQ = new AtomicLong(0);
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void clean() {
        leadEventMapper.delete(null);
        receivedEmailMapper.delete(null);
        sentEmailMapper.delete(null);
        leadMapper.delete(null);
        delayedQueue.clearAll();
    }

    private CreateLeadRequest highIntentLead() {
        return new CreateLeadRequest(
                "it-" + SEQ.incrementAndGet() + "@example.com", "ACME Corp", "US", null, null, null);
    }

    private CreateLeadRequest doomedLead() {
        return new CreateLeadRequest(
                "it-" + SEQ.incrementAndGet() + "@mailinator.com", "ACME Corp", "US", null, null, null);
    }

    private LeadContext currentStatus(Long id) {
        return leadMapper.selectById(id);
    }

    // ============================================================
    // 1. 事件驱动自动推进
    // ============================================================

    @Test
    @DisplayName("事件驱动：ingest 自动推进 RAW→STEP1_SENT（背调→内容→发信）")
    void ingestAutoAdvances() {
        LeadContext lead = pipeline.ingest(highIntentLead());

        // 自动推进到 STEP1_SENT（含背调 + 内容 + 首发）
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.STEP1_SENT);
        assertThat(lead.getSentRounds()).isEqualTo(1);
        assertThat(lead.getSmtpVerified()).isTrue();
        assertThat(lead.getKeyPerson()).isNotBlank();
        // 已排 72h 静默期监听
        assertThat(delayedQueue.peekByLead(lead.getId())).extracting(DelayedEventQueue.DelayedEvent::event)
                .contains(FsmEvent.TIMEOUT_72H);
        // 3 次 accepted 转换 + 1 封已发邮件
        assertThat(leadEventMapper.selectCount(null)).isEqualTo(3);
        assertThat(sentEmailMapper.selectCount(null)).isEqualTo(1);
        SentEmail email = sentEmailMapper.selectList(null).get(0);
        assertThat(email.getStatus()).isEqualTo("sent");
        assertThat(email.getSmtpMessageId()).startsWith("mock-");
        log.info("✅ ingest auto-advanced to STEP1_SENT, email={}", email.getSubject());
    }

    @Test
    @DisplayName("背调失败：smtp 不可达 → QUALIFY_FAIL → POOL")
    void ingestResearchFail() {
        LeadContext lead = pipeline.ingest(doomedLead());
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.POOL);
        assertThat(lead.getSmtpVerified()).isFalse();
    }

    // ============================================================
    // 2. 回信判级 + 接管
    // ============================================================

    @Test
    @DisplayName("回信判级 A：onReply(询价) → INTENT_A → SALES_HANDOVER → HANDOFF")
    void replyHighIntentToHandoff() {
        Long id = pipeline.ingest(highIntentLead()).getId();

        LeadContext afterReply = pipeline.onReply(id,
                "Please send a quote for 500 IoT gateways and arrange a call.");
        assertThat(afterReply.getStatus()).isEqualTo(LeadStatus.INTENT_A);
        assertThat(afterReply.getIntentGrade()).isEqualTo(LeadStatus.INTENT_A);
        // 回信后取消 72h 定时器
        assertThat(delayedQueue.peekByLead(id)).isEmpty();
        // 回信已落库并回填判级
        assertThat(receivedEmailMapper.selectCount(null)).isEqualTo(1);

        LeadContext afterHandover = leadService.processEvent(id, FsmEvent.SALES_HANDOVER, Map.of());
        assertThat(afterHandover.getStatus()).isEqualTo(LeadStatus.HANDOFF);
        // 终态：再发事件不迁移
        leadService.processEvent(id, FsmEvent.EMAIL_REPLIED, Map.of("intentGrade", "A"));
        assertThat(currentStatus(id).getStatus()).isEqualTo(LeadStatus.HANDOFF);
    }

    @Test
    @DisplayName("回信判级 D：onReply(退订) → INTENT_D")
    void replyNoIntent() {
        Long id = pipeline.ingest(highIntentLead()).getId();
        LeadContext afterReply = pipeline.onReply(id, "Please unsubscribe me from your list.");
        assertThat(afterReply.getStatus()).isEqualTo(LeadStatus.INTENT_D);
        assertThat(receivedEmailMapper.selectList(null).get(0).getIntentGrade()).isEqualTo("D");
    }

    // ============================================================
    // 3. 追发 → DORMANT
    // ============================================================

    @Test
    @DisplayName("追发链路：3 轮超时 → DORMANT + 调度 RE_CRAWL_TRIGGER")
    void followUpToDormant() {
        Long id = pipeline.ingest(highIntentLead()).getId();
        assertThat(currentStatus(id).getSentRounds()).isEqualTo(1);

        leadService.processEvent(id, FsmEvent.TIMEOUT_72H, Map.of());
        assertThat(currentStatus(id).getSentRounds()).isEqualTo(2);

        leadService.processEvent(id, FsmEvent.TIMEOUT_72H, Map.of());
        assertThat(currentStatus(id).getSentRounds()).isEqualTo(3);

        leadService.processEvent(id, FsmEvent.TIMEOUT_72H, Map.of());
        assertThat(currentStatus(id).getStatus()).isEqualTo(LeadStatus.DORMANT);
        assertThat(delayedQueue.peekByLead(id)).extracting(DelayedEventQueue.DelayedEvent::event)
                .contains(FsmEvent.RE_CRAWL_TRIGGER);

        // 过期超时忽略
        leadService.processEvent(id, FsmEvent.TIMEOUT_72H, Map.of());
        assertThat(currentStatus(id).getStatus()).isEqualTo(LeadStatus.DORMANT);
    }

    // ============================================================
    // 4. Guard 拒绝
    // ============================================================

    @Test
    @DisplayName("Guard 拒绝：EMAIL_REPLIED 无 intentGrade → 状态不变")
    void guardDenialMissingIntentGrade() {
        Long id = pipeline.ingest(highIntentLead()).getId();
        LeadContext before = currentStatus(id);
        leadService.processEvent(id, FsmEvent.EMAIL_REPLIED, Map.of());
        assertThat(currentStatus(id).getStatus()).isEqualTo(before.getStatus());
    }

    @Test
    @DisplayName("Guard 拒绝：INTENT_B→HANDOFF 需 salesAccepted=true")
    void guardDenialIntentBHandover() {
        Long id = pipeline.ingest(highIntentLead()).getId();
        LeadContext afterReply = pipeline.onReply(id, "Interesting, please send more info.");
        assertThat(afterReply.getStatus()).isEqualTo(LeadStatus.INTENT_B);

        leadService.processEvent(id, FsmEvent.SALES_HANDOVER, Map.of());
        assertThat(currentStatus(id).getStatus()).isEqualTo(LeadStatus.INTENT_B);

        leadService.processEvent(id, FsmEvent.SALES_HANDOVER, Map.of("salesAccepted", true));
        assertThat(currentStatus(id).getStatus()).isEqualTo(LeadStatus.HANDOFF);
    }

    // ============================================================
    // 5. API 端到端
    // ============================================================

    @Test
    @DisplayName("API：POST /api/leads 自动推进到 STEP1_SENT")
    void apiCreateLeadAutoAdvances() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "email", "api-" + SEQ.incrementAndGet() + "@example.com",
                "company", "ACME Corp",
                "country", "US"));

        mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STEP1_SENT"))
                .andExpect(jsonPath("$.smtpVerified").value(true));
    }

    @Test
    @DisplayName("API：POST /api/leads/{id}/reply 判级 → INTENT_A")
    void apiReplyClassifies() throws Exception {
        String create = om.writeValueAsString(Map.of(
                "email", "api-reply-" + SEQ.incrementAndGet() + "@example.com",
                "company", "ACME Corp",
                "country", "US"));
        String created = mockMvc.perform(post("/api/leads")
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).get("id").asLong();

        mockMvc.perform(post("/api/leads/" + id + "/reply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Please send a quote, we want 500 units.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toStatus").value("INTENT_A"));
    }

    @Test
    @DisplayName("API：/api/demo/run（useRealAgent=false）端到端 HANDOFF")
    void apiDemoRunCanned() throws Exception {
        String body = om.writeValueAsString(Map.of(
                "email", "api-demo-" + SEQ.incrementAndGet() + "@example.com",
                "company", "ACME Corp",
                "country", "US",
                "useRealAgent", false));

        mockMvc.perform(post("/api/demo/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lead.status").value("HANDOFF"));
    }
}