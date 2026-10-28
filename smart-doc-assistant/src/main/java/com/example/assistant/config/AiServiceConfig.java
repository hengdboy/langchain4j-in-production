package com.example.assistant.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 升级：ChatMemoryProvider 注入 ChatMemoryStore（Redis 或内存）
 *
 * 升级要点：
 * - 之前用 new InMemoryChatMemoryStore()，重启就丢
 * - 现在注入 Spring 容器里的 ChatMemoryStore Bean（默认 Redis）
 * - 多实例部署时，所有实例共享同一份 ChatMemoryStore
 *
 * 选择哪种存储？
 * - 生产：RedisChatMemoryStore（默认）
 * - 开发/测试：InMemoryChatMemoryStore（设 chat.memory.backend=memory）
 */
@Configuration
@RequiredArgsConstructor
public class AiServiceConfig {

    private final ChatMemoryStore chatMemoryStore;

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(6)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }


    // 通过token进行回话截断
// @Bean
// public ChatMemoryProvider chatMemoryProvider(){
// return memoryId -> TokenWindowChatMemory.builder()
// .id(memoryId)
// .maxTokens(2000, new OpenAiTokenCountEstimator("qwen3.7-flash-2026-07-15"))
// .chatMemoryStore(chatMemoryStore)
// .build();
// }
}