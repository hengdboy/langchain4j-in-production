package com.example.assistant.agentic;

import com.example.assistant.tools.CalculatorTools;
import com.example.assistant.tools.RagLookupTool;
import com.example.assistant.tools.TimeTools;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ch15 新增：Supervisor 模式（纯 Agentic 自主编排）的装配类。
 *
 * <p>把三个子 Agent 与主管 Agent 都声明为 Spring Bean，与 {@code AdvancedRagConfig} 风格统一：
 * 组件在 {@code @Configuration} 里 new 并装配依赖，使用方（{@code CustomerServiceSupervisor}）只做薄封装，
 * 不再在构造器体内手写 new（那样既别扭又不利于单测/复用）。
 *
 * <p>主管 Agent 拿到用户问题后<b>自主决定</b>接下来调用哪个子 Agent、按什么顺序，
 * 直到任务完成（内部生成计划，类似 "AgentInvocation 列表"）。
 *
 * <p>三个子 Agent 各自带工具：
 * <ul>
 *   <li>退款专家：配 Ch12 的 RagLookupTool（查本地知识库）</li>
 *   <li>物流专家：配 TimeTools（算发货时效相关日期）</li>
 *   <li>技术专家：配 CalculatorTools（运费/折扣计算）</li>
 * </ul>
 *
 * <p>{@code SupervisorResponseStrategy.SUMMARY}：由主管汇总各子 Agent 的结果返回给用户。
 */
@Configuration
public class SupervisorAgentConfig {

    /** ① 退款专家：只认知识库，防编造。outputKey 让主管拿到"带键的结论"，便于判断该问题是否已回答、避免重复派活 */
    @Bean
    public UntypedAgent refundAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                                    RagLookupTool ragLookupTool) {
        return AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("refund-expert")
                .description("处理退款/退货/赔付类问题，必须查知识库后回答")
                .userMessage("你是退款政策专家。只依据知识库中的退款政策回答，资料未提及就明说不知道。"
                        + "回答必须给出明确结论（例如'运费由买家承担'或'资料未提及，无法确认'），不要含糊。")
                .outputKey("refundAnswer")
                .tools(ragLookupTool)
                .build();
    }

    /** ② 物流专家：查时间、算时效 */
    @Bean
    public UntypedAgent logisticsAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                                       TimeTools timeTools) {
        return AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("logistics-expert")
                .description("处理物流/发货/配送时效类问题")
                .userMessage("你是物流专家。需要日期或时效计算时用工具，然后简洁回答。")
                .tools(timeTools)
                .build();
    }

    /** ③ 技术专家：算数、百分比 */
    @Bean
    public UntypedAgent techAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                                  CalculatorTools calculatorTools) {
        return AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("tech-expert")
                .description("处理优惠/折扣/金额计算类问题")
                .userMessage("你是售后技术专家。涉及数值计算时用工具，给出准确结果。")
                .tools(calculatorTools)
                .build();
    }

    /** ④ 主管：自主决定派活。maxAgentsInvocations(3) 为派发子 Agent 的总次数硬上限（agentic 模块默认=10，过大易重复派活），简单客服问题 3 次足够 */
    @Bean
    public SupervisorAgent serviceSupervisor(@Qualifier("openAiChatModel") ChatModel chatModel,
                                                    ChatMemoryProvider chatMemoryProvider,
                                                    UntypedAgent refundAgent,
                                                    UntypedAgent logisticsAgent,
                                                    UntypedAgent techAgent) {
        return AgenticServices.supervisorBuilder()
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .name("customer-service-supervisor")
                .description("客服主管：把问题分派给合适的专家，汇总后回答用户。"
                        + "约束：每个子问题只派一次；若某专家已返回明确结论（尤其是 refund-expert 的"
                        + "refundAnswer），不要再就同一问题重复派活。信息不足时用已有结论汇总并说明缺口。")
                .subAgents(refundAgent, logisticsAgent, techAgent)
                .responseStrategy(SupervisorResponseStrategy.SUMMARY)
                .maxAgentsInvocations(3)
                .build();
    }
}
