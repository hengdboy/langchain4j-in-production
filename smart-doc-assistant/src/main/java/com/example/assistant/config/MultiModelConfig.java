package com.example.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多模型分工配置（Ch03/Ch07：jsonModel + creativeModel）。
 *
 * <p>⚠️ 1.19.0 API 变化：LangChain4j starter 已移除 `langchain4j.open-ai-chat-models.*`
 * 多模型自动配置，jsonModel / creativeModel 不再自动生成 Bean。
 * 这里改为手动创建，配置值仍从 application.yml 的同名 key 读取（键名不变）。
 */
@Configuration
public class MultiModelConfig {

    /**
     * 结构化输出专用模型（低温度 + JSON Mode）。
     * 被 @AiService(chatModel = "jsonModel") 显式引用（Ch07）。
     */
    @Bean("jsonModel")
    public ChatModel jsonModel(
            @Value("${langchain4j.open-ai-chat-models.json-model.api-key:${DEEPSEEK_API_KEY:sk-replace-me}}") String apiKey,
            @Value("${langchain4j.open-ai-chat-models.json-model.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${langchain4j.open-ai-chat-models.json-model.model-name:deepseek-chat}") String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.0)   // 结构化任务必须低温度
                .maxTokens(2000)
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                .build();
    }

    /**
     * 创意写作模型（高温度 + 抗重复）。
     */
    @Bean("creativeModel")
    public ChatModel creativeModel(
            @Value("${langchain4j.open-ai-chat-models.creative-model.api-key:${DEEPSEEK_API_KEY:sk-replace-me}}") String apiKey,
            @Value("${langchain4j.open-ai-chat-models.creative-model.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${langchain4j.open-ai-chat-models.creative-model.model-name:deepseek-chat}") String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(1.0)
                .topP(0.95)
                .frequencyPenalty(0.5)
                .maxTokens(4000)
                .build();
    }
}
