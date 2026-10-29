package com.example.assistant.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Ch08/Ch10：文档语义检索服务。
 *
 * <p>Ch10 起职责收窄为"只做检索"：建库完全移交 {@link DocumentIngestionService}
 * （启动时从 classpath 加载示例文档 / 手动从目录加载 / 上传文件流式入库）。
 * 本服务保留 /api/search/semantic 接口行为不变，检索内容从硬编码文档变为真实入库文档。
 *
 * <p>注入的是 EmbeddingModel 接口，local（本地 ONNX）/ bailian（阿里百炼）切换对业务无感。
 * @author hengdboy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 语义搜索：嵌入查询 → 检索 Top-K
     */
    public List<SearchHit> search(String query, int topK, double minScore) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        // 做业务过滤更精准效率更高
       // Filter filter = MetadataFilterBuilder.metadataKey("bizId").isEqualTo("customer");
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
             // .filter(filter)
                .build();
        return embeddingStore.search(request).matches().stream()
                .map(this::toHit)
                .toList();
    }

    private SearchHit toHit(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        return new SearchHit(
                match.score(),
                segment.metadata().getString("docId"),
                segment.metadata().getString("category"),
                segment.text()
        );
    }

    /**
     * 检索结果 DTO（record 可直接序列化为 JSON）
     */
    public record SearchHit(double score, String docId, String category, String content) {
    }
}
