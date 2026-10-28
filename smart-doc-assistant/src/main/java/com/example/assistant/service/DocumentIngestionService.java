package com.example.assistant.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.example.assistant.service.LexicalSegmentIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ：文档处理流水线核心（Ingestion Pipeline）。
 *
 * <p>完整流水线：加载(Load) → 解析(Parse) → 清洗(Transform) → 分块(Split) → 嵌入(Embed) → 入库(Store)。
 *
 * <p>三种数据源：
 * <ul>
 * <li>classpath 目录（启动自动入库，随 jar 打包）</li>
 * <li>文件系统目录（手动触发，生产环境常用）</li>
 * <li>上传文件（MultipartFile 流式解析，不落盘）</li>
 * </ul>
 *
 * <p>解析器按扩展名自动选择：pdf → PDFBox，txt/md/html → 纯文本。
 * 分块使用 DocumentSplitters.recursive（段落→行→句子→词 逐级递归），
 * chunk-size / overlap 由 application.yml 配置，可环境变量覆盖。
 */
@Slf4j
@Service
public class DocumentIngestionService implements ApplicationRunner {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentCleaner documentCleaner;
    private final LexicalSegmentIndex lexicalSegmentIndex;

    private final String docsClasspath;
    private final int chunkSize;
    private final int chunkOverlap;
    private final boolean parentChild;
    private final int parentChunkSize;
    private final int parentChunkOverlap;
    private final int childChunkSize;
    private final int childChunkOverlap;

    /** 累计统计（进程内）：已入库文档数 / 分块数 */
    private final AtomicInteger documentCount = new AtomicInteger();
    private final AtomicInteger segmentCount = new AtomicInteger();

    public DocumentIngestionService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            DocumentCleaner documentCleaner,
            LexicalSegmentIndex lexicalSegmentIndex,
            @Value("${chat.document-pipeline.docs-classpath:docs}") String docsClasspath,
            @Value("${chat.document-pipeline.chunk-size:500}") int chunkSize,
            @Value("${chat.document-pipeline.chunk-overlap:50}") int chunkOverlap,
            @Value("${chat.rag.parent-child:true}") boolean parentChild,
            @Value("${chat.rag.parent-chunk-size:1200}") int parentChunkSize,
            @Value("${chat.rag.parent-chunk-overlap:150}") int parentChunkOverlap,
            @Value("${chat.rag.child-chunk-size:400}") int childChunkSize,
            @Value("${chat.rag.child-chunk-overlap:50}") int childChunkOverlap) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentCleaner = documentCleaner;
        this.lexicalSegmentIndex = lexicalSegmentIndex;
        this.docsClasspath = docsClasspath;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.parentChild = parentChild;
        this.parentChunkSize = parentChunkSize;
        this.parentChunkOverlap = parentChunkOverlap;
        this.childChunkSize = childChunkSize;
        this.childChunkOverlap = childChunkOverlap;
    }

    /** 启动时：从 classpath 加载示例文档库并建库 */
    @Override
    public void run(ApplicationArguments args) {
        try {
            ingestClasspathDocs();
        } catch (Exception e) {
            log.error("启动建库失败", e);
        }
    }

    /**
     * 数据源一：classpath 目录（示例文档，随 jar 打包）。
     *
     * <p>按扩展名分拨选解析器：PDF 二进制绝不能进 TextDocumentParser（ 10.4.4 事故——
     * UTF-8 硬转会把它转成乱码块静默入库，检索相似度还能到 0.5+）。
     */
    public synchronized void ingestClasspathDocs() {
        var fs = FileSystems.getDefault();
        List<Document> documents = new ArrayList<>();
        // PDF 走 PDFBox
        documents.addAll(ClassPathDocumentLoader.loadDocuments(
                docsClasspath, fs.getPathMatcher("glob:**.pdf"),
                new ApachePdfBoxDocumentParser()));
        // 纯文本（txt/md）走 Text
        documents.addAll(ClassPathDocumentLoader.loadDocuments(
                docsClasspath, fs.getPathMatcher("glob:**.{txt,md}"),
                new TextDocumentParser()));
        if (documents.isEmpty()) {
            log.warn(" classpath:{} 下没有发现文档", docsClasspath);
            return;
        }
        log.info("从 classpath:{} 加载到 {} 个文档", docsClasspath, documents.size());
        ingest(documents);
    }

    /** 数据源二：文件系统目录（生产环境：服务器上的文档目录） */
    public synchronized void ingestFromDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        List<Document> documents;
        try (var files = Files.list(dir)) {
            documents = files
                    .filter(Files::isRegularFile)
                    .map(path -> FileSystemDocumentLoader.loadDocument(
                            path, parserFor(path.getFileName().toString())))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + directoryPath, e);
        }
        if (documents.isEmpty()) {
            log.warn(" 目录 {} 下没有发现文档", directoryPath);
            return;
        }
        log.info("从目录 {} 加载到 {} 个文档", directoryPath, documents.size());
        ingest(documents);
    }

    /** 数据源三：上传文件（流式解析，不落盘） */
    public synchronized void ingestUpload(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        try {
            Document document = parserFor(fileName).parse(file.getInputStream());
            log.info("上传文档 {} 解析成功（{} 字符）", fileName, document.text().length());
            ingest(List.of(document));
        } catch (IOException e) {
            throw new RuntimeException("上传文件解析失败: " + fileName, e);
        }
    }

    /** 清空向量库（重建时调用） */
    public synchronized void clearStore() {
        embeddingStore.removeAll();
        lexicalSegmentIndex.clear();
        documentCount.set(0);
        segmentCount.set(0);
        log.info("向量库已清空");
    }

    public PipelineStats stats() {
        return new PipelineStats(
                documentCount.get(),
                segmentCount.get(),
                embeddingModel.dimension(),
                chunkSize,
                chunkOverlap);
    }

    /**
     * 核心流水线：盖章 → 清洗 → 分块 → 嵌入 → 入库。
     * 所有数据源最终都汇聚到这里。
     */
    private void ingest(List<Document> documents) {
        // 0. 盖元数据章：docId/category 此前只有读侧没有写侧，检索结果里永远是 null
        stampMetadata(documents);

        // 1. 清洗：去 BOM/零宽/多余空白/分隔线，统一换行
        List<Document> cleaned = documentCleaner.transformAll(documents);
        log.info("清洗完成（{} → {} 文档）", documents.size(), cleaned.size());

        // 2. 分块：父子分块（默认开）或扁平分块（ 旧行为）
        List<TextSegment> segments = parentChild
                ? splitIntoParentChild(cleaned)
                : DocumentSplitters.recursive(chunkSize, chunkOverlap).splitAll(cleaned);
        log.info("  分块完成：{} 个文档 → {} 个块（parent-child={}）",
                cleaned.size(), segments.size(), parentChild);

        // 2.5 填充关键词检索索引（混合检索的"全文"一路）
        lexicalSegmentIndex.addAll(segments);

        // 3. 嵌入
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 4. 入库
        embeddingStore.addAll(embeddings, segments);

        documentCount.addAndGet(cleaned.size());
        segmentCount.addAndGet(segments.size());
        log.info("入库完成：累计 {} 文档 / {} 块（embedding 维度={}）",
                documentCount.get(), segmentCount.get(), embeddingModel.dimension());
    }

    /**
     * 给文档补上 docId / category 元数据（三个数据源统一在这里盖章）。
     *
     * <p>加载器只会写 file_name，这里从它派生：docId = 去扩展名的文件名；
     * category = 文件名第一段（product-faq → product；没有分隔线的取 docId 本身）。
     * 扁平分块时 DocumentSplitter 会把文档 metadata 遗传给每个块；
     * 父子分块则由 {@link #splitIntoParentChild} 显式写入子块。
     */
    private void stampMetadata(List<Document> documents) {
        int seq = 0;
        for (Document doc : documents) {
            String fileName = doc.metadata().getString("file_name");
            String docId = fileName == null || fileName.isBlank()
                    ? "doc" + seq++
                    : fileName.replaceAll("\\.[^.]+$", "");
            String category = docId.contains("-")
                    ? docId.substring(0, docId.indexOf('-'))
                    : docId;
            doc.metadata().put("docId", docId);
            doc.metadata().put("category", category);
        }
    }

    /**
     * 父子分块：先把文档切成较大的「父块」，再把每个父块切成较小的「子块」。
     * 入库的是子块（小、语义聚焦、召回准），但每个子块的 metadata 带上父块文本（parentText）
     * 与 parentId——检索时按 parentId 展开为父块、去重后喂给 LLM（大、上下文足）。
     */
    private List<TextSegment> splitIntoParentChild(List<Document> cleaned) {
        DocumentSplitter parentSplitter = DocumentSplitters.recursive(parentChunkSize, parentChunkOverlap);
        DocumentSplitter childSplitter = DocumentSplitters.recursive(childChunkSize, childChunkOverlap);
        List<TextSegment> children = new ArrayList<>();
        int docSeq = 0;
        for (Document doc : cleaned) {
            String docId = doc.metadata().getString("docId");
            String category = doc.metadata().getString("category");
            List<TextSegment> parents = parentSplitter.split(doc);
            int p = 0;
            for (TextSegment parent : parents) {
                String parentId = (docId == null ? "doc" + docSeq : docId) + "#P" + p;
                // 把父块包成 Document 再切子块，父块 metadata（docId/category）会遗传给子块
                List<TextSegment> kids = childSplitter.split(Document.from(parent.text(), parent.metadata()));
                int c = 0;
                for (TextSegment kid : kids) {
                    Metadata kidMeta = new Metadata()
                            .put("docId", docId == null ? "" : docId)
                            .put("category", category == null ? "" : category)
                            .put("parentId", parentId)
                            .put("parentText", parent.text())
                            .put("childIndex", String.valueOf(c));
                    children.add(TextSegment.from(kid.text(), kidMeta));
                    c++;
                }
                p++;
            }
            docSeq++;
        }
        return children;
    }

    /** 按文件扩展名选择解析器 */
    private DocumentParser parserFor(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser();
        }
        // txt / md / html / json 等纯文本格式
        return new TextDocumentParser();
    }

    /** 流水线统计结果 */
    public record PipelineStats(int documentCount, int segmentCount, int dimension, int chunkSize, int chunkOverlap) {}
}
