package com.example.hiring.observability;

import com.example.hiring.entity.AgentCall;
import com.example.hiring.mapper.AgentCallMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.29 CostChatModelListener 集成测试（agent_call 表落库）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@SpringBootTest
class CostChatModelListenerTest {

    @Autowired
    private CostChatModelListener listener;

    @Autowired
    private AgentCallMapper agentCallMapper;

    @AfterEach
    void cleanup() {
        AgentCallContext.clear();
    }

    @Test
    @DisplayName("【Context】set/clear 正确管理 ThreadLocal")
    void testContextThreadLocal() {
        AgentCallContext.set(42L, "MatchEvaluatorAgent");
        assertThat(AgentCallContext.currentCandidateId()).isEqualTo(42L);
        assertThat(AgentCallContext.currentAgentName()).isEqualTo("MatchEvaluatorAgent");
        AgentCallContext.clear();
        assertThat(AgentCallContext.currentCandidateId()).isNull();
        assertThat(AgentCallContext.currentAgentName()).isNull();
        log.info("ThreadLocal 上下文管理正确");
    }

    @Test
    @DisplayName("【Listener】onResponse 落 agent_call 表")
    void testOnResponsePersists() {
        long before = agentCallMapper.selectCount(null);
        AgentCallContext.set(99L, "TestAgent");
        ChatRequest req = ChatRequest.builder().messages(List.of(UserMessage.from("test"))).build();
        ChatResponse resp = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        ChatModelResponseContext ctx = new ChatModelResponseContext(resp, req, ModelProvider.OPEN_AI, new HashMap<>());
        listener.onResponse(ctx);
        long after = agentCallMapper.selectCount(null);
        assertThat(after).isEqualTo(before + 1);

        List<AgentCall> calls = agentCallMapper.selectList(null);
        AgentCall latest = calls.get(calls.size() - 1);
        assertThat(latest.getCandidateId()).isEqualTo(99L);
        assertThat(latest.getAgentName()).isEqualTo("TestAgent");
        assertThat(latest.getStatus()).isEqualTo("success");
        log.info("agent_call 落库成功：id={} agent={} status={}", latest.getId(), latest.getAgentName(), latest.getStatus());
    }

    @Test
    @DisplayName("【Listener】onError 落 agent_call 表 status=failed")
    void testOnErrorPersists() {
        long before = agentCallMapper.selectCount(null);
        AgentCallContext.set(100L, "FailAgent");
        ChatRequest req = ChatRequest.builder().messages(List.of(UserMessage.from("test"))).build();
        ChatModelErrorContext ctx = new ChatModelErrorContext(
                new RuntimeException("API quota exceeded"), req, ModelProvider.OPEN_AI, new HashMap<>());
        listener.onError(ctx);
        long after = agentCallMapper.selectCount(null);
        assertThat(after).isEqualTo(before + 1);

        List<AgentCall> calls = agentCallMapper.selectList(null);
        AgentCall latest = calls.get(calls.size() - 1);
        assertThat(latest.getCandidateId()).isEqualTo(100L);
        assertThat(latest.getStatus()).isEqualTo("failed");
        assertThat(latest.getErrorMessage()).contains("quota");
        log.info("错误埋点落库成功：id={} status={}", latest.getId(), latest.getStatus());
    }
}