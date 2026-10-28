package com.example.hiring.agent;

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
 * InviteContentAgent / ReplyClassifierAgent / InterviewQuestionAgent /
 * EvaluationAgent 单元测试（mock ChatModel，合并一个文件省样板）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@DisplayName("邀请/分类/出题/评价 Agent 单元测试")
class InviteContentAgentTest {

    private ChatModel mockChatModel;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
    }

    private void mockResponse(String content) {
        when(mockChatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(content))
                .metadata(ChatResponseMetadata.builder().modelName("mock-model").build())
                .build());
    }

    @Test
    @DisplayName("【邀请】生成邮件（带 tone/cta 参数）")
    void testInvite() {
        mockResponse("""
                {"subject":"面试邀请","body":"您好，欢迎面试","language":"zh"}
                """);
        InviteContentAgent agent = AgenticServices.agentBuilder(InviteContentAgent.class)
                .chatModel(mockChatModel).build();
        InviteContentAgent.InviteEmail e = agent.generate("张伟", "Java 后端", "warm", "calendar-link");
        assertThat(e.subject()).isEqualTo("面试邀请");
        assertThat(e.language()).isEqualTo("zh");
    }

    @Test
    @DisplayName("【分类】confirmed / declined 两分支")
    void testClassify() {
        mockResponse("""
                {"decision":"confirmed","reasoning":"同意面试时间"}
                """);
        ReplyClassifierAgent agent = AgenticServices.agentBuilder(ReplyClassifierAgent.class)
                .chatModel(mockChatModel).build();
        assertThat(agent.classify("张伟", "可以，周四下午").decision()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("【出题】5 题 + 重点提示")
    void testQuestions() {
        mockResponse("""
                {"questions":["Q1","Q2","Q3","Q4","Q5"],"focusNote":"深挖 RAG"}
                """);
        InterviewQuestionAgent agent = AgenticServices.agentBuilder(InterviewQuestionAgent.class)
                .chatModel(mockChatModel).build();
        InterviewQuestionAgent.QuestionSet q = agent.generate("Java 后端", "JD", "{}");
        assertThat(q.questions()).hasSize(5);
        assertThat(q.focusNote()).contains("RAG");
    }

    @Test
    @DisplayName("【评价】PASS 汇总")
    void testEvaluation() {
        mockResponse("""
                {"overall":"PASS","rating":4,"strengths":["基础扎实"],
                 "concerns":["无"],"summary":"推荐进 offer"}
                """);
        EvaluationAgent agent = AgenticServices.agentBuilder(EvaluationAgent.class)
                .chatModel(mockChatModel).build();
        EvaluationAgent.EvaluationSummary s = agent.summarize("Java 后端", "{}", "答得不错，通过");
        assertThat(s.overall()).isEqualTo("PASS");
        assertThat(s.rating()).isEqualTo(4);
    }
}
