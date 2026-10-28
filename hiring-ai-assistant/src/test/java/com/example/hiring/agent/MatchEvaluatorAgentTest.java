package com.example.hiring.agent;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import com.example.hiring.guardrail.DiscriminationGuardrail;
import com.example.hiring.guardrail.PiiRedactionGuardrail;
import com.example.hiring.guardrail.PromptInjectionGuardrail;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ch24 MatchEvaluatorAgent 单元测试（mock ChatModel + 双输出护栏接线验证）。
 *
 * <h2>重点</h2>
 * <p>验证 AgentBuilder 挂载的 discriminationGuardrail 真的会拦截
 * "理由里引用受保护特征"的产出——这是 Ch25 §25.3 的核心机制。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@DisplayName("MatchEvaluatorAgent 单元测试（匹配评估 + 合规护栏）")
class MatchEvaluatorAgentTest {

    private ChatModel mockChatModel;
    private MatchEvaluatorAgent agent;
    private MatchEvaluatorAgent agentNoGuard;

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        agent = AgenticServices.agentBuilder(MatchEvaluatorAgent.class)
                .chatModel(mockChatModel)
                .inputGuardrails(new PromptInjectionGuardrail())
                .outputGuardrails(new DiscriminationGuardrail(), new PiiRedactionGuardrail())
                .build();
        agentNoGuard = AgenticServices.agentBuilder(MatchEvaluatorAgent.class)
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

    private static final String CLEAN_VERDICT = """
            {"score":82,"verdict":"SHORTLIST",
             "reasons":["命中技能 Java、Spring、LangChain4j","RAG 项目经验与岗位方向一致"]}
            """;

    @Test
    @DisplayName("【评估】干净产出正常解析")
    void testCleanVerdict() {
        mockResponse(CLEAN_VERDICT);
        MatchEvaluatorAgent.MatchVerdict v = agent.evaluate("Java 后端", "JD", "{}");
        assertThat(v.score()).isEqualTo(82);
        assertThat(v.verdict()).isEqualTo("SHORTLIST");
        assertThat(v.reasons()).hasSize(2);
    }

    @Test
    @DisplayName("【护栏】理由引用年龄 → DiscriminationGuardrail 拦截（异常根因含提示）")
    void testDiscriminationBlocked() {
        mockResponse("""
                {"score":80,"verdict":"SHORTLIST",
                 "reasons":["候选人 28 岁年富力强","技能匹配"]}
                """);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> agent.evaluate("Java 后端", "JD", "{}"));
        assertThat(thrown).isInstanceOf(Exception.class);
        // AgentInvocationException 包装了根因：沿 cause 链找护栏提示
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root.getMessage()).contains("受保护特征");
    }

    @Test
    @DisplayName("【对照】同一产出无护栏时放行（证明拦截来自护栏而非解析）")
    void testSameOutputPassesWithoutGuard() {
        mockResponse("""
                {"score":80,"verdict":"SHORTLIST","reasons":["候选人 28 岁年富力强","技能匹配"]}
                """);
        MatchEvaluatorAgent.MatchVerdict v = agentNoGuard.evaluate("Java 后端", "JD", "{}");
        assertThat(v.score()).isEqualTo(80);
    }

    @Test
    @DisplayName("【护栏单测】DiscriminationGuardrail 词表命中各分支")
    void testGuardrailTerms() {
        DiscriminationGuardrail guard = new DiscriminationGuardrail();
        for (String term : new String[]{"35岁", "女性候选人", "已婚已育", "杭州户籍"}) {
            OutputGuardrailResult r = guard.validate(
                    dev.langchain4j.data.message.AiMessage.from("理由：" + term));
            assertThat(r.result().name()).as("term=" + term).startsWith("FATAL");
        }
        assertThat(guard.validate(dev.langchain4j.data.message.AiMessage.from(
                "理由：Spring 经验扎实")).result().name()).startsWith("SUCCESS");
    }
}
