package com.example.assistant.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

/**
 * Ch08/Ch11：Embedding 组件配置。
 *
 * <p>chat.embedding.provider 决定用哪个 Embedding 模型：
 * <ul>
 * <li>local（默认）：本地 ONNX All-MiniLM-L6-v2，384 维，零成本零 API Key，适合开发调试</li>
 * <li>bailian：阿里百炼 text-embedding-v4（OpenAI 兼容接口），1024 维，中文生产推荐</li>
 * </ul>
 *
 * <p>chat.embedding.store（Ch11 新增）决定用哪个向量库：
 * <ul>
 * <li>inmemory（默认）：进程内存储，重启即失，零依赖</li>
 * <li>redis：Redis Stack（含 RediSearch 模块），可持久化， dimension 必须与模型匹配</li>
 * </ul>
 *
 * <p>切换只改配置，业务代码零改动（依赖倒置的好处）。
 */
@Configuration
public class EmbeddingConfig {

    /** 本地 ONNX 模型：All-MiniLM-L6-v2，384 维，多语言（含中文） */
    @Bean
    @ConditionalOnProperty(name = "chat.embedding.provider",
            havingValue = "local", matchIfMissing = true)
    public EmbeddingModel localEmbeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /** 阿里百炼模型：text-embedding-v4，1024 维（OpenAI 兼容接口） */
    @Bean
    @ConditionalOnProperty(name = "chat.embedding.provider", havingValue = "bailian")
    public EmbeddingModel bailianEmbeddingModel(
            @Value("${chat.embedding.bailian.api-key}") String apiKey,
            @Value("${chat.embedding.bailian.base-url}") String baseUrl,
            @Value("${chat.embedding.bailian.model-name}") String modelName) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    /**
     * 内存向量库（默认）：进程内存储，重启即失，零依赖，开发调试首选。
     * 可 serializeToFile/fromFile 做轻量持久化。
     */
    @Bean
    @ConditionalOnProperty(name = "chat.embedding.store",
            havingValue = "inmemory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * Redis 连接：统一 Jedis 客户端，复用连接池，与 ChatMemoryStoreConfig 同风格。
     * 仅 chat.embedding.store=redis 时创建。
     */
    @Bean
    @ConditionalOnProperty(name = "chat.embedding.store", havingValue = "redis")
    public UnifiedJedis unifiedJedisForEmbedding(
            @Value("${chat.embedding.redis.host:localhost}") String host,
            @Value("${chat.embedding.redis.port:6379}") int port) {
        return new UnifiedJedis(new HostAndPort(host, port));
    }

    /**
     * Redis 向量库：需要 Redis Stack（含 RediSearch 模块），数据可持久化。
     *
     * <p> dimension 必须与 EmbeddingModel 匹配：local=384 / bailian=1024。
     * 注意：索引只在「不存在」时创建（RedisEmbeddingStore 构造时检查）；
     * 索引已存在时 dimension 参数会被静默忽略——改维度前必须先删旧索引
     * （redis-cli FT.DROPINDEX &lt;index-name&gt;）或换一个新的 index-name。
     */
    @Bean
    @ConditionalOnProperty(name = "chat.embedding.store", havingValue = "redis")
    public EmbeddingStore<TextSegment> redisEmbeddingStore(
            UnifiedJedis unifiedJedis,
            @Value("${chat.embedding.redis.index-name:embedding-index}") String indexName,
            @Value("${chat.embedding.redis.dimension:384}") int dimension) {
        return RedisEmbeddingStore.builder()
                .unifiedJedis(unifiedJedis)
                .indexName(indexName)
                .dimension(dimension)
                .build();
    }
}
