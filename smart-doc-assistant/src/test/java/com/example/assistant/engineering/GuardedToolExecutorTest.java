package com.example.assistant.engineering;

import com.example.assistant.guardrail.GuardrailMetrics;
import com.example.assistant.guardrail.GuardrailProperties;
import com.example.assistant.guardrail.GuardrailViolation;
import com.example.assistant.guardrail.ToolGuard;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ch16：执行期护栏的"显式失败"语义。
 *
 * <p>回归点：修复前拦截后返回普通字符串，框架按 {@code isError=false} 处理，
 * 审计把"被拒绝"记成成功，模型也可能把拒绝当成普通观察值继续自由作答。
 *
 * @author hengdboy
 */
@DisplayName("DynamicToolProvider：护栏拒绝返回 isError + blocked 标记")
class GuardedToolExecutorTest {

    private GuardrailProperties props(List<String> userTools, int rateLimit) {
        GuardrailProperties props = new GuardrailProperties();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("user", userTools);
        props.getTool().setRoleAllowedTools(roles);
        props.getTool().setRateLimitPerMinute(rateLimit);
        return props;
    }

    private DynamicToolProvider provider(ToolGuard guard) {
        // 不注册任何工具、不调用 provideTools，只需要代理 executor
        return new DynamicToolProvider(List.of(), null, guard);
    }

    private static ToolCatalog.Entry entry(String name, String toolResult) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(name)
                .build();
        ToolExecutor executor = (request, memoryId) -> toolResult;
        return new ToolCatalog.Entry(spec, executor, "calc", java.util.Set.of(), java.util.Set.of());
    }

    private static ToolExecutionRequest request(String name) {
        return ToolExecutionRequest.builder().name(name).arguments("{}").build();
    }

    private static InvocationContext context(Object memoryId) {
        return InvocationContext.builder().chatMemoryId(memoryId).build();
    }

    @Test
    @DisplayName("拒绝：isError=true + blocked attributes，真实工具不执行")
    void deniedResultIsExplicitError() {
        ToolGuard guard = new ToolGuard(props(List.of("searchKnowledgeBase"), 30), new GuardrailMetrics());
        ToolExecutor executor = provider(guard).guardedExecutor(entry("calculate", "42"), "user");

        ToolExecutionResult result = executor.executeWithContext(request("calculate"), context(1L));

        assertThat(result.isError()).isTrue();
        assertThat(result.resultText())
                .contains("工具调用被拒绝")
                .contains("无权调用工具 [calculate]")
                .contains("不要编造或推测");
        assertThat(result.attributes())
                .containsEntry("blocked", true)
                .containsEntry("blockReason", "tool.not_in_role_whitelist");
    }

    @Test
    @DisplayName("放行：正常委托真实执行器，isError=false 且不带 blocked")
    void allowedDelegatesToRealExecutor() {
        ToolGuard guard = new ToolGuard(props(List.of("calculate"), 30), new GuardrailMetrics());
        ToolExecutor executor = provider(guard).guardedExecutor(entry("calculate", "42"), "user");

        ToolExecutionResult result = executor.executeWithContext(request("calculate"), context(1L));

        assertThat(result.isError()).isFalse();
        assertThat(result.resultText()).isEqualTo("42");
        assertThat(result.attributes()).doesNotContainKey("blocked");
    }

    @Test
    @DisplayName("直接走 execute() 时显式抛异常，而不是返回成功文本")
    void legacyExecuteThrows() {
        ToolGuard guard = new ToolGuard(props(List.of("searchKnowledgeBase"), 30), new GuardrailMetrics());
        ToolExecutor executor = provider(guard).guardedExecutor(entry("calculate", "42"), "user");

        assertThatThrownBy(() -> executor.execute(request("calculate"), 1L))
                .isInstanceOf(GuardrailViolation.class)
                .hasMessageContaining("无权调用工具 [calculate]");
    }

    @Test
    @DisplayName("限流拒绝同样标 blocked，且 reason 可区分")
    void rateLimitIsMarkedBlocked() {
        ToolGuard guard = new ToolGuard(props(List.of("calculate"), 1), new GuardrailMetrics());
        ToolExecutor executor = provider(guard).guardedExecutor(entry("calculate", "42"), "user");

        assertThat(executor.executeWithContext(request("calculate"), context(1L)).isError()).isFalse();
        ToolExecutionResult denied = executor.executeWithContext(request("calculate"), context(1L));

        assertThat(denied.isError()).isTrue();
        assertThat(denied.attributes()).containsEntry("blockReason", "tool.rate_limited");
    }

    @Test
    @DisplayName("工具层关闭：不做任何拦截，正常执行")
    void guardDisabledDelegates() {
        GuardrailProperties props = props(List.of(), 30);
        props.getTool().setEnabled(false);
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        ToolExecutor executor = provider(guard).guardedExecutor(entry("calculate", "42"), "user");

        ToolExecutionResult result = executor.executeWithContext(request("calculate"), context(1L));

        assertThat(result.isError()).isFalse();
        assertThat(result.resultText()).isEqualTo("42");
    }
}
