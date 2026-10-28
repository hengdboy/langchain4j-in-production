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
 * Ch24 IntentClassifierAgent 单元测试（mock ChatModel）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@DisplayName("IntentClassifierAgent 单元测试（意向分级）")
class IntentClassifierAgentTest {

    private ChatModel mockChatModel;
    private IntentClassifierAgent agent;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        agent = AgenticServices.agentBuilder(IntentClassifierAgent.class)
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
    @DisplayName("【A 级】明确询价/约电话 → grade=A + suggestCall")
    void testClassifyA() {
        mockResponse("""
                {
                  "grade": "A",
                  "reasoning": "明确索要报价单并约电话",
                  "suggestCall": true
                }
                """);

        IntentClassifierAgent.IntentAssessment a = agent.classify(
                "Please send a quote for 500 gateways and call me.", "ACME Corp", "IoT");

        assertThat(a.grade()).isEqualTo("A");
        assertThat(a.suggestCall()).isTrue();
        log.info("✅ A 级：reasoning={}", a.reasoning());
    }

    @Test
    @DisplayName("【D 级】退订 → grade=D")
    void testClassifyD() {
        mockResponse("""
                {
                  "grade": "D",
                  "reasoning": "客户要求退订",
                  "suggestCall": false
                }
                """);

        IntentClassifierAgent.IntentAssessment a = agent.classify(
                "Please unsubscribe me.", "ACME Corp", "IoT");

        assertThat(a.grade()).isEqualTo("D");
        assertThat(a.suggestCall()).isFalse();
        log.info("✅ D 级：reasoning={}", a.reasoning());
    }
}