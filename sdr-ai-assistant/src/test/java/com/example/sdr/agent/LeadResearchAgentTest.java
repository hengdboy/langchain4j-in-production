package com.example.sdr.agent;

import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ch24 LeadResearchAgent 单元测试（mock ChatModel + 真实 mock 工具）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@DisplayName("LeadResearchAgent 单元测试（背调）")
class LeadResearchAgentTest {

    private ChatModel mockChatModel;
    private LeadResearchAgent agent;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        // 挂 3 个 mock 工具（Agent 内部自主调用，此处 mock 模型直接返回最终 JSON）
        agent = AgenticServices.agentBuilder(LeadResearchAgent.class)
                .chatModel(mockChatModel)
                .tools(new CustomsLookupTool(), new SmtpVerifyTool(), new KpFinderTool())
                .build();
    }

    private void mockResponse(String content) {
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from(content))
                .metadata(ChatResponseMetadata.builder().modelName("mock-model").build())
                .build();
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(resp);
    }

    @Test
    @DisplayName("【通过】SMTP 可达 + KP 找到 → passed=true")
    void testResearchPassed() {
        mockResponse("""
                {
                  "passed": true,
                  "confidence": 0.9,
                  "smtpVerified": true,
                  "kpFound": true,
                  "kpName": "Sarah Chen",
                  "kpEmail": "sarah.chen@acme.com",
                  "importRecords": ["smart home devices"],
                  "reasoning": "SMTP 可达且有明确决策人"
                }
                """);

        LeadResearchAgent.ResearchReport r =
                agent.research("ACME Corp", "US", "sarah.chen@acme.com");

        assertThat(r.passed()).isTrue();
        assertThat(r.smtpVerified()).isTrue();
        assertThat(r.kpEmail()).isEqualTo("sarah.chen@acme.com");
        assertThat(r.importRecords()).contains("smart home devices");
        log.info("✅ 背调通过：confidence={} kp={}", r.confidence(), r.kpName());
    }

    @Test
    @DisplayName("【失败】SMTP 不可达 → passed=false")
    void testResearchFailed() {
        mockResponse("""
                {
                  "passed": false,
                  "confidence": 0.6,
                  "smtpVerified": false,
                  "kpFound": false,
                  "kpName": null,
                  "kpEmail": null,
                  "importRecords": [],
                  "reasoning": "邮箱为一次性域名，无法送达"
                }
                """);

        LeadResearchAgent.ResearchReport r =
                agent.research("Unknown Ltd", "US", "x@mailinator.com");

        assertThat(r.passed()).isFalse();
        assertThat(r.smtpVerified()).isFalse();
        log.info("✅ 背调失败识别：confidence={}", r.confidence());
    }
}