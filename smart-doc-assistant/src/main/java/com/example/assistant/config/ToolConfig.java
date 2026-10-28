package com.example.assistant.config;

import com.example.assistant.assistant.ToolAssistant;
import com.example.assistant.tools.CalculatorTools;
import com.example.assistant.tools.OrderTools;
import com.example.assistant.tools.TimeTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.search.vector.VectorToolSearchStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 新增：工具类 Bean + ToolAssistant AiService 配置
 *
 * 关键设计：
 * - 工具类是普通 POJO（不需要 @Component）
 * - 在 Config 里集中注册 + 注入到 AiService
 * - maxSequentialToolsInvocations 防 LLM 无限循环
 */
@Configuration
@RequiredArgsConstructor
public class ToolConfig {

    private final ChatMemoryProvider chatMemoryProvider;

    @Bean
    public TimeTools timeTools() {
        return new TimeTools();
    }

    @Bean
    public CalculatorTools calculatorTools() {
        return new CalculatorTools();
    }

    @Bean
    public OrderTools orderTools() {
        return new OrderTools();
    }

    @Bean
    public ToolAssistant toolAssistant(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return AiServices.builder(ToolAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(timeTools(), calculatorTools(), orderTools()).maxToolCallingRoundTrips(10)  // ← 防循环
                .build();
    }
}