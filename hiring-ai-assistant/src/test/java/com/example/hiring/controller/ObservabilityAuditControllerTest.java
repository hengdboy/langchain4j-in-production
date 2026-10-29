package com.example.hiring.controller;

import com.example.hiring.core.FsmEvent;
import com.example.hiring.core.CandidateStatus;
import com.example.hiring.core.CandidateTransitionedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.0 Observability / Audit Controller 集成测试。
 *
 * @author hengdboy
 * @since v1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Test
    @DisplayName("【Observability】/api/observability/summary 返回汇总")
    void testObservabilitySummary() throws Exception {
        mockMvc.perform(get("/api/observability/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").exists())
                .andExpect(jsonPath("$.totalInputTokens").exists())
                .andExpect(jsonPath("$.byAgent").exists());
    }

    @Test
    @DisplayName("【Audit】发布 CandidateTransitionedEvent 后 /api/audit/candidate/{id} 可见记录")
    void testAuditEventRecorded() throws Exception {
        publisher.publishEvent(new CandidateTransitionedEvent(
                999L, CandidateStatus.APPLIED, CandidateStatus.SHORTLISTED, FsmEvent.SCREEN_PASS));

        mockMvc.perform(get("/api/audit/candidate/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("update"))
                .andExpect(jsonPath("$[0].resourceType").value("candidate"))
                .andExpect(jsonPath("$[0].candidateId").value(999));
    }
}