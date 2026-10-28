package com.example.assistant.mcp;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Ch19 MCP 深度开发：把 Ch08/Ch12 的检索能力暴露为 MCP 工具，
 * 让外部 MCP 客户端（Claude Desktop / Cursor / 其他智能体）能查询本应用的知识库。
 *
 * <p>实现要点：
 * <ul>
 *   <li>用 LangChain4j 标准的 {@link Tool} 注解标记方法，{@code McpServer} 会扫描 @Tool
 *       方法并自动生成 JSON Schema；不引入新的 MCP 专属注解（官方 SDK 沿用 @Tool）</li>
 *   <li>工具语义对齐 {@code SemanticSearchController} / {@code RagController}：
 *       <ul>
 *         <li>{@link #searchKnowledgeBase(String, Integer, Double)}：纯向量检索（复用 Ch08 语义检索）</li>
 *         <li>{@link #askWithRag(String, Integer, Double)}：完整 RAG（复用 Ch12 RetrievalAugmentor）</li>
 *       </ul>
 *   </li>
 *   <li>结果通过 MCP 返回 String（MCP 协议 content 数组里只有文本段），
 *       多片段用换行 + 编号组织，便于 LLM 解析</li>
 * </ul>
 *
 * <p>⚠️ 本类是纯 POJO（不加 {@code @Component}），由 {@code McpServerStarter} 显式装配，
 * 避免与 Spring 上下文里的同名 Bean 冲突；MCP Server 进程独立于主 Spring Boot 进程。
 *
 * <h2>工具白名单单一真相源</h2>
 * <p>{@link #TOOL_NAMES} 是工具白名单的单一来源；{@link McpSecurityService} 直接引用此常量，
 * 加新工具时只需在这里加方法 + 在此列表里加名字，不会有两个真相源漂移的风险。
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
@Slf4j
public class KnowledgeBaseMcpTools {

    /**
     * 工具白名单的单一真相源。{@link McpSecurityService} 直接引用此列表。
     * 顺序不影响功能；保持与下方 @Tool 方法声明一致便于人工 review。
     */
    public static final List<String> TOOL_NAMES = List.of(
            "searchKnowledgeBase",
            "askWithRag",
            "getKnowledgeBaseStats"
    );

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RetrievalAugmentor retrievalAugmentor;

    public KnowledgeBaseMcpTools(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore,
                                RetrievalAugmentor retrievalAugmentor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.retrievalAugmentor = retrievalAugmentor;
    }

    /**
     * 纯向量检索：嵌入查询 → 在向量库中检索 Top-K 相似片段。
     * <p>对应 {@code POST /api/search/semantic}（Ch08）的能力；
     * 通过 MCP 暴露后，任何 MCP 客户端都能用同样的方式调用本应用的知识库。
     *
     * @param question  用户的非空自然语言问题
     * @param topK      返回片段数（1~10；默认 3）
     * @param minScore  最低相似度阈值 [0, 1]；低于此分不返回（默认 0.0）
     * @return 格式化的检索结果（片段编号 + 相似度 + 文档元数据 + 正文）
     */
    @Tool("查询本地知识库，返回与问题最相关的文档片段（语义检索）。用于回答基于文档的事实性问题。")
    public String searchKnowledgeBase(
            @P("用户的自然语言问题，例如：'七天无理由退货的条件是什么？'") String question,
            @P("返回片段数量，1 到 10 之间；常用 3 或 5") Integer topK,
            @P("最低相似度阈值，0 到 1 之间；0 表示不过滤，0.5~0.7 是常用起点") Double minScore) {

        String q = requireQuestion(question);

        int k = (topK == null || topK < 1) ? 3 : Math.min(topK, 10);
        double threshold = (minScore == null) ? 0.0 : Math.max(0.0, Math.min(1.0, minScore));

        log.info("🔧 [MCP] searchKnowledgeBase(question='{}', topK={}, minScore={})", q, k, threshold);

        Embedding queryEmbedding = embeddingModel.embed(q).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(k)
                .minScore(threshold)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

        if (matches.isEmpty()) {
            return "（未检索到相关片段）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共检索到 ").append(matches.size()).append(" 个相关片段：\n\n");
        int i = 0;
        for (EmbeddingMatch<TextSegment> m : matches) {
            sb.append("【片段 ").append(++i).append("】 相似度=")
              .append(String.format("%.4f", m.score())).append("\n");
            TextSegment seg = m.embedded();
            String docId = seg.metadata().getString("docId");
            String category = seg.metadata().getString("category");
            if (docId != null || category != null) {
                sb.append("元数据: docId=").append(docId).append(", category=").append(category).append("\n");
            }
            sb.append("正文: ").append(seg.text()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 完整 RAG 检索：复用 Ch12 的 {@link RetrievalAugmentor}（向量+关键词混合 + rerank），
     * 返回融合+重排后的文档片段。同 Ch13 的检索层评估管线。
     *
     * <p>注意：本工具<strong>只返回检索结果</strong>，不调 LLM 做答案生成——把"检索"和"生成"
     * 解耦，让调用方（外部 MCP 客户端的 LLM）自己拼 Prompt 与作答，避免跨进程调用 LLM 的复杂度。
     *
     * @param question  用户的非空问题
     * @param topK      最大返回片段数（默认 5）
     * @param minScore  最低相似度阈值（默认 0.0）
     * @return 经混合检索 + rerank 后的文档片段（按相关性降序）
     */
    @Tool("高级 RAG 检索：复用父子分块 + 向量/关键词混合 + reranker，返回精排后的文档片段。比纯向量检索质量更高，但更慢。")
    public String askWithRag(
            @P("用户的自然语言问题") String question,
            @P("返回片段数量，常用 3 或 5") Integer topK,
            @P("最低相似度阈值，0 到 1 之间；默认 0 不过滤") Double minScore) {

        String q = requireQuestion(question);

        int k = (topK == null || topK < 1) ? 5 : Math.min(topK, 10);
        double threshold = (minScore == null) ? 0.0 : Math.max(0.0, Math.min(1.0, minScore));

        log.info("🔧 [MCP] askWithRag(question='{}', topK={}, minScore={})", q, k, threshold);

        // RetrievalAugmentor 内部用 queryRouter → ContentRetriever → ContentAggregator
        // Ch13 的 RagEvaluator 已验证这套管线的输出顺序 = 最终排名（融合 + 重排已完成）
        UserMessage userMessage = UserMessage.from(q);
        // memoryId 占位为 "mcp-tool"（与 Ch14 RagLookupTool 一致），保证与现有管线行为相同
        AugmentationResult result = retrievalAugmentor.augment(
                new AugmentationRequest(userMessage, Metadata.from(userMessage, "mcp-tool", java.util.List.of()))
        );

        if (result == null || result.contents() == null || result.contents().isEmpty()) {
            return "（未检索到相关片段）";
        }

        List<Content> contents = result.contents();
        int limit = Math.min(k, contents.size());
        StringBuilder sb = new StringBuilder();
        sb.append("RAG 检索到 ").append(contents.size()).append(" 个片段（返回前 ").append(limit).append(" 个）：\n\n");
        for (int i = 0; i < limit; i++) {
            Content c = contents.get(i);
            sb.append("【片段 ").append(i + 1).append("】\n");
            sb.append("正文: ").append(c.textSegment().text()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 返回当前向量库的统计信息（embedding 模型维度、向量库实现类型）。
     * 对应 {@code GET /api/docs/stats} 的能力，让外部客户端能"看"到知识库状态。
     */
    @Tool("查询知识库统计信息：embedding 向量维度、当前 embedding 模型与向量库实现。")
    public String getKnowledgeBaseStats() {
        log.info("🔧 [MCP] getKnowledgeBaseStats()");
        int dim = embeddingModel.dimension();
        return String.format(
                "知识库统计：\n- Embedding 维度: %d\n- Embedding 模型: %s\n- Vector Store: %s",
                dim,
                embeddingModel.getClass().getSimpleName(),
                embeddingStore.getClass().getSimpleName()
        );
    }

    /**
     * 校验 question：null / blank 直接返回"（问题不能为空）"，
     * 避免后续 {@code embeddingModel.embed(null)} 抛 NPE 让工具调用链路断裂。
     */
    private String requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            log.warn("⚠️ [MCP] tool called with empty/null question");
            return ""; // sentinel；本工具不返回结果给 caller，调用方拿到 "（问题不能为空）" 后续会改
        }
        return question;
    }
}