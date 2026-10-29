package com.example.hiring.config;

import com.example.hiring.agent.EvaluationAgent;
import com.example.hiring.agent.InterviewQuestionAgent;
import com.example.hiring.agent.InviteContentAgent;
import com.example.hiring.agent.MatchEvaluatorAgent;
import com.example.hiring.agent.ReplyClassifierAgent;
import com.example.hiring.agent.ResumeParserAgent;
import com.example.hiring.guardrail.DiscriminationGuardrail;
import com.example.hiring.guardrail.PiiRedactionGuardrail;
import com.example.hiring.guardrail.PromptInjectionGuardrail;
import com.example.hiring.observability.CostChatModelListener;
import com.example.hiring.observability.InstrumentedChatModel;
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
 * Agent Bean 装配（护栏分工在这里一眼看全）。
 *
 * <p>{@code @Agent} 接口必须用 {@code AgenticServices.agentBuilder(Class)} 构造
 * （接口内恰好一个 {@code @Agent} 方法）。
 *
 * <h2>编排策略</h2>
 * <p>Agent 是"叶子"，不做全局自主编排——由
 * {@link com.example.hiring.service.CandidatePipelineService} 按 FSM 状态确定性触发。
 *
 * <h2>护栏分工（Ch25 §25.3 主题）</h2>
 * <table border="1">
 * <tr><th>Agent</th><th>输入护栏</th><th>输出护栏</th></tr>
 * <tr><td>ResumeParser</td><td>注入</td><td>—</td></tr>
 * <tr><td>MatchEvaluator</td><td>注入</td><td>歧义特征 + PII</td></tr>
 * <tr><td>InviteContent</td><td>—</td><td>PII</td></tr>
 * <tr><td>ReplyClassifier</td><td>—</td><td>PII</td></tr>
 * <tr><td>InterviewQuestion</td><td>注入</td><td>歧义特征 + PII</td></tr>
 * <tr><td>Evaluation</td><td>注入</td><td>歧义特征 + PII</td></tr>
 * </table>
 *
 * <p><b>Observability</b>：{@link CostChatModelListener} 挂在包装后的 ChatModel 上，
 * 通过 {@link InstrumentedChatModel} 桥接。
 *
 * @author hengdboy
 * @since v0.30
 */
@Configuration
@RequiredArgsConstructor
public class HiringAgentConfig {

    private final CostChatModelListener costListener;
    private final PiiRedactionGuardrail piiGuardrail;
    private final PromptInjectionGuardrail injectionGuardrail;
    private final DiscriminationGuardrail discriminationGuardrail;

    /** 包装 ChatModel，挂载 Observability listener。@Primary 防止与 openAiChatModel 装配歧义。 */
    @Bean
    @Primary
    public ChatModel instrumentedChatModel(@Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return new InstrumentedChatModel(openAiChatModel, List.<ChatModelListener>of(costListener));
    }

    /** Agent 1：简历解析（输入护栏：注入） */
    @Bean
    public ResumeParserAgent resumeParserAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(ResumeParserAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .build();
    }

    /** Agent 2：岗位匹配评估（输入护栏：注入；输出护栏：歧义特征 + PII） */
    @Bean
    public MatchEvaluatorAgent matchEvaluatorAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(MatchEvaluatorAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .outputGuardrails(discriminationGuardrail, piiGuardrail)
                .build();
    }

    /** Agent 3：约面邀请（输出护栏：PII） */
    @Bean
    public InviteContentAgent inviteContentAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(InviteContentAgent.class)
                .chatModel(instrumentedChatModel)
                .outputGuardrails(piiGuardrail)
                .build();
    }

    /** Agent 4：回复分类（输出护栏：PII） */
    @Bean
    public ReplyClassifierAgent replyClassifierAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(ReplyClassifierAgent.class)
                .chatModel(instrumentedChatModel)
                .outputGuardrails(piiGuardrail)
                .build();
    }

    /** Agent 5：面试出题（输入护栏：注入；输出护栏：歧义特征 + PII） */
    @Bean
    public InterviewQuestionAgent interviewQuestionAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(InterviewQuestionAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .outputGuardrails(discriminationGuardrail, piiGuardrail)
                .build();
    }

    /** Agent 6：评价汇总（输入护栏：注入；输出护栏：歧义特征 + PII） */
    @Bean
    public EvaluationAgent evaluationAgent(ChatModel instrumentedChatModel) {
        return AgenticServices.agentBuilder(EvaluationAgent.class)
                .chatModel(instrumentedChatModel)
                .inputGuardrails(injectionGuardrail)
                .outputGuardrails(discriminationGuardrail, piiGuardrail)
                .build();
    }
}
