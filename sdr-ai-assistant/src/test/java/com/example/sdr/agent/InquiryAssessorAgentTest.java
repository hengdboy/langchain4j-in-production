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
 * Ch24 InquiryAssessorAgent 单元测试（mock ChatModel）。
 *
 * <h2>为什么拆分成独立 Agent？</h2>
 * <p>{@code AgenticServices.agentBuilder()} 校验接口内<strong>恰好一个</strong> @Agent 方法
 * （javap 核实 AgentUtil.validateAgentClass），所以 LeadSearchAgent 和 InquiryAssessorAgent
 * 必须各为一个单方法接口。
 *
 * <h2>与 LeadSearchAgentTest 的关系</h2>
 * <p>Ch07 结构化输出（record + @Description）的解析测试在这里覆盖，
 * LeadSearchAgentTest 专注于 Google Dorks 生成测试。
 *
 * @author langchain4j-tutorial
 * @since v0.24
 */
@Slf4j
@DisplayName("InquiryAssessorAgent 单元测试")
class InquiryAssessorAgentTest {

    private ChatModel mockChatModel;
    private InquiryAssessorAgent agent;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        // @Agent 注解接口用 AgenticServices.agentBuilder（不是 AiServices）
        agent = AgenticServices.agentBuilder(InquiryAssessorAgent.class)
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
    @DisplayName("【正常】评估高意向询盘 → 解析为 record")
    void testAssessHighIntent() {
        String mockJson = """
                {
                  "intentScore": 0.85,
                  "companyName": "ACME Corp",
                  "productCategory": "smart home devices",
                  "hasDecisionMaker": true,
                  "reasoning": "客户明确提到采购 1000 台 IoT 网关，有具体数量和联系人"
                }
                """;
        mockResponse(mockJson);

        InquiryAssessorAgent.InquiryAssessment result = agent.assessInquiry(
                "Hi, I am John from ACME Corp. We want to order 1000 IoT gateways. Please contact our procurement manager sarah@acme.com");

        assertThat(result.intentScore()).isEqualTo(0.85);
        assertThat(result.companyName()).isEqualTo("ACME Corp");
        assertThat(result.hasDecisionMaker()).isTrue();
        assertThat(result.productCategory()).contains("smart home");
        log.info("✅ 高意向询盘：intent={} company={} hasKP={}",
                result.intentScore(), result.companyName(), result.hasDecisionMaker());
    }

    @Test
    @DisplayName("【低质量】评分 0.2 的询盘")
    void testLowQualityInquiry() {
        String mockJson = """
                {
                  "intentScore": 0.2,
                  "companyName": "Unknown",
                  "productCategory": "unknown",
                  "hasDecisionMaker": false,
                  "reasoning": "邮件过于简短，无具体产品需求"
                }
                """;
        mockResponse(mockJson);

        InquiryAssessorAgent.InquiryAssessment result =
                agent.assessInquiry("Hi, what products do you have?");

        assertThat(result.intentScore()).isEqualTo(0.2);
        assertThat(result.hasDecisionMaker()).isFalse();
        log.info("✅ 低质量询盘识别：intent={}", result.intentScore());
    }

    @Test
    @DisplayName("【边界】空邮件正文")
    void testEmptyEmail() {
        mockResponse("{}");
        InquiryAssessorAgent.InquiryAssessment result = agent.assessInquiry("");
        // JSON {} 反序列化为默认值
        assertThat(result.intentScore()).isNull();
        log.info("✅ 空邮件处理正确");
    }
}