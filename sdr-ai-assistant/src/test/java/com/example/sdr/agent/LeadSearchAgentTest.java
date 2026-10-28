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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ch24 LeadSearchAgent 单元测试（mock ChatModel）。
 *
 * <h2>拆分说明</h2>
 * <p>{@code assessInquiry} 已被拆分到 {@link InquiryAssessorAgent}。
 * 本测试只覆盖 Google Dorks 生成能力；询盘评估测试见
 * {@link InquiryAssessorAgentTest}。
 *
 * <h2>关于 @Agent 独立调用的 WARN</h2>
 * <p>{@code AgenticServices.agentBuilder} 设计的 @Agent 接口用于 Ch15 多 Agent 编排
 * （supervisor / sequence / parallel），独立测试时会触发 WARN：
 * "Improper invocation of a standalone agent outside of an agentic system"。
 * 这是设计提示，不是错误——测试场景必然独立调用。
 *
 * @author langchain4j-tutorial
 * @since v0.24
 */
@Slf4j
@DisplayName("LeadSearchAgent 单元测试（仅 Dorks 生成）")
class LeadSearchAgentTest {

    private ChatModel mockChatModel;
    private LeadSearchAgent agent;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        // @Agent 注解接口用 AgenticServices.agentBuilder（不是 AiServices）
        agent = AgenticServices.agentBuilder(LeadSearchAgent.class)
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
    @DisplayName("【正常】生成 3-5 条 Dorks")
    void testGenerateDorksNormal() {
        String mockDorks = String.join("\n", List.of(
                "site:linkedin.com \"CEO\" \"consumer electronics\" US",
                "site:importgenius.com \"smart home\" 2024",
                "inurl:linkedin.com \"Procurement Manager\" \"IoT\""
        ));
        mockResponse(mockDorks);

        List<String> dorks = agent.generateDorks("US", "smart home", "IoT");

        assertThat(dorks).hasSize(3);
        assertThat(dorks.get(0)).contains("linkedin.com");
        log.info("✅ mock ChatModel 返回 {} 条 Dorks", dorks.size());
    }

    @Test
    @DisplayName("【空响应】ChatModel 返回空字符串")
    void testGenerateDorksEmptyResponse() {
        mockResponse("");
        List<String> dorks = agent.generateDorks("US", "X", "Y");
        assertThat(dorks).isEmpty();
        log.info("✅ 空响应处理正确");
    }
}