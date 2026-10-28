package com.example.assistant.config;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Ch05 新增：ChatMemoryStore 的 Redis 持久化配置
 *
 * 关键设计：
 * - 单一 ChatMemoryStore Bean 被 ChatMemoryProvider 引用
 * - 多会话通过 memoryId 区分，每个会话一份独立存储
 * - TTL 30 天：长期不活跃的会话自动清理
 *
 * 启用 Redis：在 application.yml 设 chat.memory.backend=redis
 * 不启用（默认）：使用内存版 ChatMemoryStore
 */
@Configuration
@Slf4j
public class ChatMemoryStoreConfig {

    /**
     * Redis 版 ChatMemoryStore
     */
    @Bean
    @ConditionalOnProperty(name = "chat.memory.backend", havingValue = "redis", matchIfMissing = true)
    public ChatMemoryStore redisChatMemoryStore(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${chat.memory.ttl-days:30}") int ttlDays) {

        log.info("Initializing RedisChatMemoryStore at {}:{} (TTL {} days)", host, port, ttlDays);

        RedisChatMemoryStore.Builder builder = RedisChatMemoryStore.builder()
                .host(host)
                .port(port)
                // 命名空间前缀：默认 keyPrefix 为空，chat memory 会以裸 memoryId（如 "3"）作为 Redis key，
                // 极易与同实例其它数据（计数器/列表/旧 embedding 残留等）撞 key 并触发
                // "Existing key has wrong Redis type"。加前缀隔离，且历史脏 key 不会误读。
                .prefix("chat-memory:")
                .ttl(Duration.ofDays(ttlDays).toSeconds());

        if (password != null && !password.isBlank()) {
            builder.password(password);
        }

        return builder.build();
    }

    /**
     * 内存版 ChatMemoryStore（fallback）
     */
    @Bean
    @ConditionalOnProperty(name = "chat.memory.backend", havingValue = "memory")
    public ChatMemoryStore inMemoryChatMemoryStore() {
        log.warn("Using InMemoryChatMemoryStore — conversations will be lost on restart!");
        return new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
    }
}