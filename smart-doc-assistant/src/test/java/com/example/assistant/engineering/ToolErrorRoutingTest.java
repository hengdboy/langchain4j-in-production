package com.example.assistant.engineering;

import com.example.assistant.guardrail.GuardrailMetrics;
import com.example.assistant.guardrail.GuardrailProperties;
import com.example.assistant.guardrail.ToolGuard;
import com.example.assistant.tools.CalculatorTools;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch16：参数错误必须进 {@code toolArgumentsErrorHandler}，而不是 execution handler。
 *
 * <p>回归点：provider 自建执行器默认 {@code wrapToolArgumentsExceptions=false}，
 * 参数解析异常不是 {@code ToolArgumentsException}，会被工具异常处理器接走；
 * 同时验证业务异常只有实现 {@link dev.langchain4j.exception.ToolErrorVisibleToLlm}
 * 才会把消息交给模型，内部异常只回通用文案。
 *
 * @author hengdboy
 */
@DisplayName("工具错误路由：arguments handler vs execution handler")
class ToolErrorRoutingTest {

    private static final String GENERIC_FAILURE = "工具执行失败，请稍后重试或告知用户。";

    public interface TestAgent {
        String chat(@MemoryId Long sessionId, @UserMessage String question);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("类型不匹配：走 arguments handler，且不回显原始异常")
    void typeMismatchRoutesToArgumentsHandler() {
        ScriptedChatModel model = new ScriptedChatModel("calculate",
                "{\"a\":\"abc\",\"op\":\"ADD\",\"b\":2}");
        AtomicBoolean argumentsHandled = new AtomicBoolean();
        AtomicBoolean executionHandled = new AtomicBoolean();

        String toolResult = run(List.of(new CalculatorTools()), List.of("calculate"),
                model, argumentsHandled, executionHandled);

        assertThat(argumentsHandled).isTrue();
        assertThat(executionHandled).isFalse();
        assertThat(toolResult)
                .contains("参数不合法")
                .contains("类型或取值")
                .doesNotContain("not convertable")
                .doesNotContain("java.lang.String");
    }

    @Test
    @DisplayName("缺必填参数：文案点出参数名，而不是只说格式不正确")
    void missingRequiredParamRoutesToArgumentsHandler() {
        ScriptedChatModel model = new ScriptedChatModel("calculate", "{}");
        AtomicBoolean argumentsHandled = new AtomicBoolean();
        AtomicBoolean executionHandled = new AtomicBoolean();

        String toolResult = run(List.of(new CalculatorTools()), List.of("calculate"),
                model, argumentsHandled, executionHandled);

        assertThat(argumentsHandled).isTrue();
        assertThat(executionHandled).isFalse();
        assertThat(toolResult).contains("缺少必填参数").contains("\"a\"");
    }

    @Test
    @DisplayName("坏 JSON：走 arguments handler，不把解析细节回给模型")
    void malformedJsonRoutesToArgumentsHandler() {
        ScriptedChatModel model = new ScriptedChatModel("calculate", "{\"a\":");
        AtomicBoolean argumentsHandled = new AtomicBoolean();
        AtomicBoolean executionHandled = new AtomicBoolean();

        String toolResult = run(List.of(new CalculatorTools()), List.of("calculate"),
                model, argumentsHandled, executionHandled);

        assertThat(argumentsHandled).isTrue();
        assertThat(executionHandled).isFalse();
        assertThat(toolResult).contains("参数不合法");
    }

    @Test
    @DisplayName("业务异常：标记 ToolErrorVisibleToLlm 后按原文交给模型")
    void visibleBusinessErrorReachesLlm() {
        ScriptedChatModel model = new ScriptedChatModel("calculate",
                "{\"a\":1,\"op\":\"DIVIDE\",\"b\":0}");
        AtomicBoolean argumentsHandled = new AtomicBoolean();
        AtomicBoolean executionHandled = new AtomicBoolean();

        String toolResult = run(List.of(new CalculatorTools()), List.of("calculate"),
                model, argumentsHandled, executionHandled);

        assertThat(executionHandled).isTrue();
        assertThat(argumentsHandled).isFalse();
        assertThat(toolResult).contains("除数不能为零");
    }

    @Test
    @DisplayName("内部异常：只回通用文案，SQL/连接串不进模型")
    void internalErrorIsNotLeakedToLlm() {
        ScriptedChatModel model = new ScriptedChatModel("fail", "{}");
        AtomicBoolean argumentsHandled = new AtomicBoolean();
        AtomicBoolean executionHandled = new AtomicBoolean();

        String toolResult = run(List.of(new FailingTools()), List.of("fail"),
                model, argumentsHandled, executionHandled);

        assertThat(executionHandled).isTrue();
        assertThat(argumentsHandled).isFalse();
        assertThat(toolResult).isEqualTo(GENERIC_FAILURE).doesNotContain("secret");
    }

    private String run(List<Object> tools, List<String> allowedTools, ScriptedChatModel model,
                       AtomicBoolean argumentsHandled, AtomicBoolean executionHandled) {
        GuardrailProperties props = new GuardrailProperties();
        props.getTool().setRoleAllowedTools(Map.of("user", allowedTools));
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        DynamicToolProvider provider =
                new DynamicToolProvider(tools, new FixedRouterModel(), guard);

        ToolArgumentsErrorHandler argumentsHandler = (error, context) -> {
            argumentsHandled.set(true);
            return ToolErrorHandlerResult.text(
                    ToolErrorMessages.argumentError(context.toolExecutionRequest().name(), error));
        };
        ToolExecutionErrorHandler executionHandler = (error, context) -> {
            executionHandled.set(true);
            return ToolExecutionErrorHandler
                    .sendGenericMessageToLlmUnlessVisibleToLlm(GENERIC_FAILURE)
                    .handle(error, context);
        };

        TestAgent agent = AiServices.builder(TestAgent.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(10))
                .toolProvider(provider)
                .toolArgumentsErrorHandler(argumentsHandler)
                .toolExecutionErrorHandler(executionHandler)
                .maxToolCallingRoundTrips(3)
                .build();

        RequestContext.set("tenantA", "u1", "user");
        try {
            agent.chat(1L, "计算一下");
        } finally {
            RequestContext.clear();
        }
        return model.toolResultText;
    }

    private static final class FixedRouterModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("calc")).build();
        }
    }

    /**
     * 第一次调用吐出工具调用，拿到工具结果后返回终稿；只用于把路由跑通，不发网络请求。
     */
    private static final class ScriptedChatModel implements ChatModel {

        private final String toolName;
        private final String toolArguments;
        private String toolResultText;
        private int calls;

        private ScriptedChatModel(String toolName, String toolArguments) {
            this.toolName = toolName;
            this.toolArguments = toolArguments;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            ToolExecutionResultMessage toolResult = null;
            for (ChatMessage message : request.messages()) {
                if (message instanceof ToolExecutionResultMessage result) {
                    toolResult = result;
                }
            }
            if (toolResult != null) {
                this.toolResultText = toolResult.text();
                return ChatResponse.builder().aiMessage(AiMessage.from("已完成")).build();
            }
            calls++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("call-" + calls)
                            .name(toolName)
                            .arguments(toolArguments)
                            .build()))
                    .build();
        }
    }

    static final class FailingTools {

        @Tool("总是抛内部异常，用于验证细节不泄露给模型")
        public String fail() {
            throw new IllegalStateException("jdbc:mysql://secret-host/db password=xxx");
        }
    }
}
