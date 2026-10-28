package com.example.lab;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;

/**
 * Lab1 合同知识库：把 classpath:corpus/ 下的合同文档切块、嵌入、入库、检索。
 *
 * <p>切块策略是本 lab 的核心变量——默认 recursive(450, 80)。合同是"条款 + 表格"
 * 混合结构：表格被拦腰切开时，"第 1 期 | 2026-03-05 | 20,400"这类行离开了
 * 表头就失去语义，这是读者要在 README 实验里亲手踩的坑。
 *
 * <p>入库三步（切块 → 嵌入 → 写库）故意手写而不包 Ingestor，
 * 读者改任何一步都看得见。
 */
public final class ContractKnowledge {

    private final InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private int chunkCount;
    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    private final int chunkSize;
    private final int overlap;

    public ContractKnowledge(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /** 把 classpath:corpus/ 下所有合同文档切块入库，返回块数。 */
    public int ingestClasspathCorpus() {
        List<Document> documents = ClassPathDocumentLoader.loadDocuments(
                "corpus", new dev.langchain4j.data.document.parser.TextDocumentParser());
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, overlap);
        List<TextSegment> segments = splitter.splitAll(documents);
        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment).content();
            store.add(embedding, segment);
        }
        this.chunkCount = segments.size();
        return segments.size();
    }

    /** 检索：返回前 k 个块（按余弦相似度排序）。 */
    public List<TextSegment> search(String query, int k) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = store.search(
                new EmbeddingSearchRequest(queryEmbedding, k, null, null));
        return result.matches().stream().map(EmbeddingMatch::embedded).toList();
    }

    /** 当前库里的块数（split 时记下的计数）。 */
    public int storeSize() {
        return chunkCount;
    }
}
