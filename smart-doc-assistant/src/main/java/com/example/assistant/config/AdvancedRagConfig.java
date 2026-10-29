package com.example.assistant.config;

import com.example.assistant.rag.DashScopeRerankScoringModel;
import com.example.assistant.rag.DeduplicatingContentAggregator;
import com.example.assistant.rag.EmbeddingScoringModel;
import com.example.assistant.rag.KeywordContentRetriever;
import com.example.assistant.rag.ParentChildEmbeddingStoreRetriever;
import com.example.assistant.service.LexicalSegmentIndex;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Ch12：高级 RAG 管线装配（v0.10）。
 *
 * <p>把三件"提升检索质量"的武器组装进一个 {@link RetrievalAugmentor}：
 * <ol>
 * <li><b>父子分块</b>：{@link ParentChildEmbeddingStoreRetriever} 把向量命中的子块展开为父块，兼顾召回准 + 上下文足。</li>
 * <li><b>混合检索</b>：{@link QueryRouter} 把查询同时路由到「向量路」与「关键词路」，
 * 两路结果由 {@link ReRankingContentAggregator} 内部的 {@code ReciprocalRankFuser}（RRF, k=60）融合。</li>
 * <li><b>重排序</b>：融合后用 {@link ScoringModel}（本项目用 {@link EmbeddingScoringModel} 双编码器）精排，只留 topN。</li>
 * </ol>
 *
 * <p>所有组件离线可跑、零新依赖：向量与重排共用现有 EmbeddingModel，关键词路用进程内 {@link LexicalSegmentIndex}。
 * @author hengdboy
 */
@Configuration
public class AdvancedRagConfig {

    @Bean
    public ScoringModel embeddingScoringModel(
            EmbeddingModel embeddingModel,
            @Value("${chat.rag.reranker.provider:embedding}") String rerankProvider,
            @Value("${chat.rag.reranker.dashscope.api-key:}") String dashscopeApiKey,
            @Value("${chat.rag.reranker.dashscope.base-url:https://dashscope.aliyuncs.com/compatible-api/v1/reranks}") String dashscopeBaseUrl,
            @Value("${chat.rag.reranker.dashscope.top-n:10}") int dashscopeTopN) {

        if ("dashscope".equalsIgnoreCase(rerankProvider) && !dashscopeApiKey.isBlank()) {
            return new DashScopeRerankScoringModel(dashscopeApiKey, dashscopeBaseUrl, dashscopeTopN);
        }
        // fallback：bi-encoder（复用 embedding 模型，零成本）
        // 注意 provider=dashscope 但没配 key 时也走这里——不抛异常，保证离线/子进程（MCP）能启动
        return new EmbeddingScoringModel(embeddingModel);
    }

    @Bean
    @Primary
    public RetrievalAugmentor advancedRetrievalAugmentor(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            LexicalSegmentIndex lexicalSegmentIndex,
            ScoringModel embeddingScoringModel,
            @Value("${chat.rag.vector.max-results:5}") int vectorMaxResults,
            @Value("${chat.rag.vector.min-score:0.0}") Double vectorMinScore,
            @Value("${chat.rag.keyword.enabled:true}") boolean keywordEnabled,
            @Value("${chat.rag.keyword.max-results:5}") int keywordMaxResults,
            @Value("${chat.rag.reranker.min-score:0.0}") Double rerankMinScore,
            @Value("${chat.rag.reranker.max-results:5}") int rerankMaxResults) {

        // 向量一路（父子展开）
        EmbeddingStoreContentRetriever vector = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(vectorMaxResults)
                .minScore(vectorMinScore)
                .build();
        ContentRetriever vectorRetriever = new ParentChildEmbeddingStoreRetriever(vector);

        // 混合一路：关键词（可关闭）；两路一起进路由器
        List<ContentRetriever> retrievers = new ArrayList<>();
        retrievers.add(vectorRetriever);
        if (keywordEnabled) {
            retrievers.add(new KeywordContentRetriever(lexicalSegmentIndex, keywordMaxResults));
        }
        QueryRouter router = query -> retrievers;

        // 融合（RRF）+ 重排；外层包去重，消掉向量/关键词两路的重复父块
        ReRankingContentAggregator rerankingAggregator = ReRankingContentAggregator.builder()
                .scoringModel(embeddingScoringModel)
                .minScore(rerankMinScore)
                .maxResults(rerankMaxResults)
                .build();
        ContentAggregator aggregator = new DeduplicatingContentAggregator(rerankingAggregator);

        return DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .contentAggregator(aggregator)
                .build();
    }
}
