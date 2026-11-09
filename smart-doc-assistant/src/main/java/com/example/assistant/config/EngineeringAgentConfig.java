package com.example.assistant.config;

import com.example.assistant.engineering.AgentAuditService;
import com.example.assistant.engineering.DynamicToolProvider;
import com.example.assistant.engineering.EngineeringAgent;
import com.example.assistant.guardrail.ToolGuard;
import com.example.assistant.tools.CalculatorTools;
import com.example.assistant.tools.RagLookupTool;
import com.example.assistant.tools.TimeTools;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 新增：Agent 工程化装配
 *
 * <p>给 Agent 加上六件工程化能力（均 源码核实过 1.19.0 真实 API）：
 * <ol>
 * <li><b>动态工具注入</b>：{@code toolProvider(DynamicToolProvider)}——按查询按需给工具；</li>
 * <li><b>工具调用审计</b>：{@code beforeToolExecution / afterToolExecution} 回调 → AgentAuditService；</li>
 * <li><b>并发工具执行</b>：{@code executeToolsConcurrently()}——模型一次要调多个工具时并行执行；</li>
 * <li><b>可观测性</b>：{@code registerListener(AiServiceListener)}——记录 AiService 调用完成事件（含 tokenUsage 成本）；</li>
 * <li><b>错误处理</b>：{@code toolExecutionErrorHandler / toolArgumentsErrorHandler / hallucinatedToolNameStrategy}——
 * 工具抛异常、参数解析失败、模型调用不存在的工具，都能兜底并引导模型纠正；</li>
 * <li><b>防循环</b>：{@code maxToolCallingRoundTrips(10)}——工具循环护栏。</li>
 * </ol>
 * @author hengdboy
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class EngineeringAgentConfig {

    private final ChatMemoryProvider chatMemoryProvider;
    private final TimeTools timeTools;
    private final CalculatorTools calculatorTools;
    private final RagLookupTool ragLookupTool;
    private final AgentAuditService agentAuditService;
    private final ToolGuard toolGuard;

    @Bean
    public DynamicToolProvider dynamicToolProvider(@Qualifier("openAiChatModel") ChatModel chatModel) {
        // 第二个参数是意图路由用的轻量模型：生产建议用小模型（便宜、快）；教学复用主模型即可
        // 第三个参数是工具执行期护栏：在 provideTools 返回的每个工具上包一层代理，
        // 模型真正执行工具前过黑名单/限流/角色白名单
        return new DynamicToolProvider(List.of(ragLookupTool, timeTools, calculatorTools), chatModel, toolGuard);
    }

    @Bean
    public EngineeringAgent engineeringAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                                             DynamicToolProvider dynamicToolProvider) {
        return AiServices.builder(EngineeringAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolProvider(dynamicToolProvider)                 // 动态工具
                .beforeToolExecution(bte -> log.debug("即将执行工具 [{}]", bte.request().name()))
                .afterToolExecution(te -> {
                    // 审计：回调可能跑在并发执行的虚拟线程上，ThreadLocal 无请求上下文，
                    // 租户/用户从 provideTools 在请求线程捕获的快照里取（DynamicToolProvider.contextFor）
                    Object memoryId = te.invocationContext().chatMemoryId();
                    String[] ctx = dynamicToolProvider.contextFor(memoryId);
                    if (ctx != null) {
                        agentAuditService.record(memoryId, ctx[0], ctx[1],
                                te.request().name(), te.request().arguments(),
                                te.result(), te.hasFailed(), te.startTime());
                    } else {
                        agentAuditService.record(memoryId,
                                te.request().name(), te.request().arguments(),
                                te.result(), te.hasFailed(), te.startTime());
                    }
                })
                .executeToolsConcurrently()                        // 并发执行
                .maxToolCallingRoundTrips(10)
                // 错误处理三件套：工具异常 / 参数解析失败 / 幻觉工具名，都兜底成友好文本引导模型纠正
                .toolExecutionErrorHandler((throwable, context) -> {
                    String tool = context.toolExecutionRequest().name();
                    log.warn("[错误处理] 工具[{}]执行异常: {}", tool, throwable.getMessage());
                    return ToolErrorHandlerResult.text(
                            "工具 [" + tool + "] 执行失败：" + throwable.getMessage()
                                    + "。请修正参数后重试，或换一种方式回答。");
                })
                .toolArgumentsErrorHandler((throwable, context) -> {
                    log.warn("[错误处理] 工具参数解析失败: {}", throwable.getMessage());
                    return ToolErrorHandlerResult.text(
                            "工具参数格式不正确：" + throwable.getMessage() + "。请重新生成参数。");
                })
                .hallucinatedToolNameStrategy(request ->
                        ToolExecutionResultMessage.from(request,
                                "工具 [" + request.name() + "] 不存在，请只使用系统提供的工具。"))
                .registerListener(completedListener())             // 可观测性
                .build();
    }

    /** 可观测性 listener：每次 AiService 调用完成打一条日志（含 token 用量 → 成本核算） */
    private AiServiceListener<AiServiceCompletedEvent> completedListener() {
        return new AiServiceListener<>() {
            @Override
            public Class<AiServiceCompletedEvent> getEventClass() {
                return AiServiceCompletedEvent.class;
            }

            @Override
            public void onEvent(@NonNull AiServiceCompletedEvent event) {
                Object result = event.result().orElse(null);
                String tokenInfo = "";
                if (result instanceof Result<?> r && r.tokenUsage() != null) {
                    TokenUsage tu = r.tokenUsage();
                    tokenInfo = String.format(" tokens: in=%s out=%s total=%s",
                            tu.inputTokenCount(), tu.outputTokenCount(), tu.totalTokenCount());
                }
                log.info("[可观测] AiService 调用完成{} result={}",
                        tokenInfo,
                        result == null ? "null"
                                : result.toString().length() > 60
                                        ? result.toString().substring(0, 60) + "…"
                                        : result);
            }
        };
    }
}
