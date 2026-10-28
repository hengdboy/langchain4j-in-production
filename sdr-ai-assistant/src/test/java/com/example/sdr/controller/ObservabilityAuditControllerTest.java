package com.example.sdr.controller;

import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.core.LeadTransitionedEvent;
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
 * @author langchain4j-tutorial
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
    @DisplayName("【Audit】发布 LeadTransitionedEvent 后 /api/audit/lead/{id} 可见记录")
    void testAuditEventRecorded() throws Exception {
        publisher.publishEvent(new LeadTransitionedEvent(
                999L, LeadStatus.RAW, LeadStatus.QUALIFIED, FsmEvent.QUALIFY_OK));

        mockMvc.perform(get("/api/audit/lead/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("update"))
                .andExpect(jsonPath("$[0].resourceType").value("lead"))
                .andExpect(jsonPath("$[0].leadId").value(999));
    }
}