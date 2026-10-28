package com.example.assistant.config;

import com.example.assistant.assistant.ResearchAgent;
import com.example.assistant.service.ManualAgentService;
import com.example.assistant.tools.CalculatorTools;
import com.example.assistant.tools.RagLookupTool;
import com.example.assistant.tools.TimeTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Ch14 新增：研究助手 Agent 配置
 *
 * <p>关键设计（与 Ch06 ToolConfig 同源）：
 * <ul>
 *   <li>工具是普通 POJO，集中在此注册为 Bean；</li>
 *   <li>{@code maxToolCallingRoundTrips(10)} 限制"模型↔工具"循环轮次，防 LLM 无限循环；</li>
 *   <li>{@code chatMemoryProvider} 提供多会话记忆隔离（@MemoryId）。</li>
 * </ul>
 *
 * <p>这里复用了 Ch06 已有的 {@code timeTools} / {@code calculatorTools} Bean，
 * 并新增 {@code ragLookupTool}（注入 Ch12 的 RetrievalAugmentor）。
 */
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ChatMemoryProvider chatMemoryProvider;
    private final TimeTools timeTools;
    private final CalculatorTools calculatorTools;
    private final RetrievalAugmentor advancedRetrievalAugmentor;

    @Bean
    public RagLookupTool ragLookupTool() {
        return new RagLookupTool(advancedRetrievalAugmentor);
    }

    @Bean
    public ResearchAgent researchAgent(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return AiServices.builder(ResearchAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(timeTools, calculatorTools, ragLookupTool())
                .maxToolCallingRoundTrips(10)   // ← 防循环：最多 10 轮工具调用
                .build();
    }

    @Bean
    public ManualAgentService manualAgentService(@Qualifier("openAiChatModel") ChatModel chatModel) {
        //FIXME 手动循环演示服务：把同一组工具交给它，复现框架底层的"模型→工具→再模型"循环。框架已经有核心实现，这个只是模拟
        return new ManualAgentService(chatModel, List.of(timeTools, calculatorTools, ragLookupTool()));
    }
}
