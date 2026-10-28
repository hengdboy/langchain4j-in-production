package com.example.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ：视觉对话模型配置。
 *
 * <p>qwen3.7-flash 原生支持图像 / 视频 / 文本输入（官方确认），且走阿里百炼的 OpenAI
 * 兼容接口（base-url = compatible-mode/v1），所以直接用 {@link OpenAiChatModel} 即可，
 * 零新增依赖。
 *
 * <p>为什么单独建一个 Bean 而不是复用默认聊天模型？
 * <ul>
 * <li>默认聊天模型是 DeepSeek（纯文本，看不了图）</li>
 * <li>视觉任务需要独立的模型名 + 更低的 temperature（描述类任务要客观准确）</li>
 * <li>Bean 名为 {@code visionChatModel}，通过 @Qualifier 显式注入，与默认模型互不干扰</li>
 * </ul>
 */
@Configuration
public class VisionModelConfig {

    @Value("${chat.multimodal.chat-model.api-key}")
    private String apiKey;

    @Value("${chat.multimodal.chat-model.base-url}")
    private String baseUrl;

    @Value("${chat.multimodal.chat-model.model-name}")
    private String modelName;

    @Bean("visionChatModel")
    public ChatModel visionChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)   // 图像描述 / OCR 要客观，温度压到 0.2
                .maxTokens(1024)
                .build();
    }
}
