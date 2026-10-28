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

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ResumeParserAgent 单元测试（mock ChatModel）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@DisplayName("ResumeParserAgent 单元测试（简历解析）")
class ResumeParserAgentTest {

    private ChatModel mockChatModel;
    private ResumeParserAgent agent;
    private final AtomicReference<String> lastPrompt = new AtomicReference<>();

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        when(mockChatModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest req = inv.getArgument(0);
            lastPrompt.set(req.messages().toString());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("""
                            {"name":"张伟","email":"zw@example.com","phone":"13800001111",
                             "yearsOfExperience":5,"skills":["Java","Spring","LangChain4j"],
                             "education":"本科","projects":["企业知识库问答系统"],"notes":null}
                            """))
                    .metadata(ChatResponseMetadata.builder().modelName("mock-model").build())
                    .build();
        });
        agent = AgenticServices.agentBuilder(ResumeParserAgent.class)
                .chatModel(mockChatModel)
                .build();
    }

    @Test
    @DisplayName("【解析】结构化字段齐全，原文进 prompt")
    void testParse() {
        ResumeParserAgent.ParsedProfile p = agent.parse("张伟 5 年 Java 经验……");
        assertThat(p.name()).isEqualTo("张伟");
        assertThat(p.yearsOfExperience()).isEqualTo(5);
        assertThat(p.skills()).contains("Java", "LangChain4j");
        assertThat(lastPrompt.get()).contains("张伟");
    }
}
