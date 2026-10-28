package com.example.assistant.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Ch12：用「现有 EmbeddingModel」实现的双编码器重排序器（bi-encoder reranker）。
 *
 * <p>{@link ScoringModel} 接口要求对每个 (query, segment) 对打分。最朴素也最稳的实现就是
 * 把 query 与 segment 各自嵌入、算余弦相似度——这正是"向量召回后再精排"的常见思路。
 * 优点：零新依赖、离线可跑（直接复用 local AllMiniLM 或 bailian 嵌入模型）。
 *
 * <p>⚠️ 诚实说明：双编码器重排 <b>弱于</b> 交叉编码器（cross-encoder，如 Cohere / BAAI bge-reranker），
 * 因为后者把 query+doc 拼在一起过一次模型，能捕捉细粒度交互。生产追求极致命中率应换交叉编码器
 * （{@code langchain4j-reranker-cohere} / {@code langchain4j-reranker-score} 提供的 {@link ScoringModel} 实现）。
 * 本章用双编码器把"重排序"这条链路完整跑通、讲透，并在 12.6 给出升级路径。
 */
public class EmbeddingScoringModel implements ScoringModel {

    private final EmbeddingModel embeddingModel;

    public EmbeddingScoringModel(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<Double> scores = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            Embedding segmentEmbedding = embeddingModel.embed(segment.text()).content();
            scores.add(cosineSimilarity(queryEmbedding, segmentEmbedding));
        }
        return Response.from(scores);
    }

    /** 余弦相似度：不假设向量已归一化，按定义计算（dot / |a||b|）。 */
    private double cosineSimilarity(Embedding a, Embedding b) {
        double[] va = a.vectorAsDoubleArray();
        double[] vb = b.vectorAsDoubleArray();
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < va.length; i++) {
            dot += va[i] * vb[i];
            na += va[i] * va[i];
            nb += vb[i] * vb[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
