package com.example.sdr.agent;

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
 * Ch24 OutreachContentAgent 单元测试（mock ChatModel）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@DisplayName("OutreachContentAgent 单元测试（开发信）")
class OutreachContentAgentTest {

    private ChatModel mockChatModel;
    private OutreachContentAgent agent;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        agent = AgenticServices.agentBuilder(OutreachContentAgent.class)
                .chatModel(mockChatModel)
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
    @DisplayName("【英文】生成含背调信息 + CTA + 退订的开发信")
    void testGenerateEn() {
        mockResponse("""
                {
                  "subject": "Partnership opportunity with ACME Corp",
                  "body": "Hi Sarah,\\n\\nWe noticed ACME Corp imports smart home devices.\\nCould we grab 15 minutes this week?\\n\\n(Unsubscribe anytime)\\n\\nBest, SDR Team",
                  "language": "en"
                }
                """);

        OutreachContentAgent.OutreachEmail e = agent.generate(
                "ACME Corp", "US", "en", "Sarah",
                "smart home devices", "smart home & IoT solutions");

        assertThat(e.subject()).contains("ACME Corp");
        assertThat(e.body()).contains("Sarah");
        assertThat(e.body()).contains("Unsubscribe");
        assertThat(e.language()).isEqualTo("en");
        log.info("✅ 英文开发信：subject=[{}] bodyChars={}", e.subject(), e.body().length());
    }

    @Test
    @DisplayName("【德语】指定语种输出")
    void testGenerateDe() {
        mockResponse("""
                {
                  "subject": "Partnerschaft mit ACME Corp",
                  "body": "Hallo Sarah, wir haben gesehen, dass ACME Corp Smart-Home-Geräte importiert.",
                  "language": "de"
                }
                """);

        OutreachContentAgent.OutreachEmail e = agent.generate(
                "ACME Corp", "DE", "de", "Sarah",
                "smart home devices", "smart home & IoT solutions");

        assertThat(e.language()).isEqualTo("de");
        assertThat(e.subject()).contains("Partnerschaft");
        log.info("✅ 德语开发信：subject=[{}]", e.subject());
    }
}