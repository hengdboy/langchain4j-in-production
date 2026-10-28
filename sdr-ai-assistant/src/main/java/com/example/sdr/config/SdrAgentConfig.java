package com.example.sdr.config;

import com.example.sdr.agent.InquiryAssessorAgent;
import com.example.sdr.agent.IntentClassifierAgent;
import com.example.sdr.agent.LeadResearchAgent;
import com.example.sdr.agent.LeadSearchAgent;
import com.example.sdr.agent.OutreachContentAgent;
import com.example.sdr.guardrail.PiiRedactionGuardrail;
import com.example.sdr.guardrail.PromptInjectionGuardrail;
import com.example.sdr.observability.CostChatModelListener;
import com.example.sdr.observability.InstrumentedChatModel;
import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Ch24 SDR Agent Bean 装配（v0.26 → v0.29）。
 *
 * <p>{@code @Agent} 接口必须用 {@code AgenticServices.agentBuilder(Class)} 构造
 * （javap 核实：AiServices 无 agentBuilder 方法；接口内恰好一个 {@code @Agent} 方法）。
 *
 * <p><b>编排策略</b>：Agent 是"叶子"，不做全局自主编排——由
 * {@link com.example.sdr.service.LeadPipelineService} 按 FSM 状态确定性触发；
 * 每个 Agent 内部可自主调用 mock 工具（如 LeadResearchAgent 的 3 个背调工具）。
 *
 * <h2>v0.29 横切</h2>
 * <ul>
 *   <li><b>Observability</b>：{@link CostChatModelListener} 挂在包装后的 ChatModel 上，
 *       通过 {@link InstrumentedChatModel} 桥接（LangChain4j 1.19 ChatModel 默认
 *       {@code listeners()} 为空，无法直接挂载）</li>
 *   <li><b>Guardrails</b>：PII / Injection 通过 AgentBuilder 的
 *       {@code .inputGuardrails()} / {@code .outputGuardrails()} 注入</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@Configuration
@RequiredArgsConstructor
public class SdrAgentConfig {

    private final CostChatModelListener costListener;
    private final PiiRedactionGuardrail piiGuardrail;
    private final PromptInjectionGuardrail injectionGuardrail;

    /**
     * 包装 ChatModel，挂载 Observability listener（v0.29）。
     *
     * <p>{@code @Primary} 让 Agent Bean 装配时优先注入此 Bean（虽然默认也是唯一 ChatModel Bean，
     * 但 Spring 看到 openAiChatModel 也会尝试装配，显式 {@code @Primary} 防歧义）。
     */
    @Bean
    @Primary
    public ChatModel instrumentedChatModel(@Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return new InstrumentedChatModel(openAiChatModel, List.<ChatModelListener>of(costListener));
    }

    /** 阶段一询盘评估 Agent（demo 真实调用；无 key 环境由 DemoService 兜底 canned） */
    @Bean
    public InquiryAssessorAgent inquiryAssessorAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(InquiryAssessorAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .build();
    }

    /** 阶段一 Dorks 生成 Agent */
    @Bean
    public LeadSearchAgent leadSearchAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(LeadSearchAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .build();
    }

    /** 阶段二 深度背调 Agent（挂 3 个 mock 工具，Agent 内部自主调用） */
    @Bean
    public LeadResearchAgent leadResearchAgent(ChatModel instrumentedChatModel,
                                               CustomsLookupTool customsLookupTool,
                                               SmtpVerifyTool smtpVerifyTool,
                                               KpFinderTool kpFinderTool) {
        return AgenticServices.agentBuilder(LeadResearchAgent.class)
                .chatModel(instrumentedChatModel)
                .tools(customsLookupTool, smtpVerifyTool, kpFinderTool)
                .inputGuardrails(injectionGuardrail)
                .build();
    }

    /** 阶段三 多语言开发信 Agent（纯生成，挂 PII 脱敏输出 guardrail） */
    @Bean
    public OutreachContentAgent outreachContentAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(OutreachContentAgent.class)
                .chatModel(instrumentedChatModel)
                .outputGuardrails(piiGuardrail)
                .build();
    }

    /** 阶段五 意向分级 Agent（纯分类，无工具） */
    @Bean
    public IntentClassifierAgent intentClassifierAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(IntentClassifierAgent.class)
                .chatModel(instrumentedChatModel)
                .outputGuardrails(piiGuardrail)
                .build();
    }
}