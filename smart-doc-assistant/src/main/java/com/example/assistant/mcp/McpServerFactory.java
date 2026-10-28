package com.example.assistant.mcp;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.mcp.protocol.McpImplementation;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * Ch19 MCP 深度开发：{@link McpServer} 构造的单一工厂方法（P2-4 修复）。
 *
 * <p>消除 {@code McpServerConfig} 与 {@code McpServerStarter} 两处重复的 McpServer 装配代码——
 * 加新工具或换 serverInfo 时只改一处。
 *
 * <h2>服务器元信息</h2>
 * <p>{@code name} 用 {@code "smart-doc-assistant"}、{@code version} 用 {@code "0.17.0"}，
 * 与 pom.xml 的 {@code <version>} 标签同步（v0.17 在 Ch19 引入）。
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
public final class McpServerFactory {

    public static final String SERVER_NAME = "smart-doc-assistant";
    public static final String SERVER_VERSION = "0.17.0";

    private McpServerFactory() {}

    /**
     * 构造带 {@link KnowledgeBaseMcpTools} 的 MCP Server。
     * 工具 Bean 通过 {@link EmbeddingModel} / {@link EmbeddingStore} / {@link RetrievalAugmentor}
     * 注入——与 Spring 上下文装配方式完全一致，保证 stdio 启动器与 HTTP 模式下行为相同。
     */
    public static McpServer create(EmbeddingModel embeddingModel,
                                   EmbeddingStore<TextSegment> embeddingStore,
                                   RetrievalAugmentor retrievalAugmentor) {
        KnowledgeBaseMcpTools tools = new KnowledgeBaseMcpTools(embeddingModel, embeddingStore, retrievalAugmentor);

        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(SERVER_NAME);
        serverInfo.setVersion(SERVER_VERSION);

        return new McpServer(List.of(tools), serverInfo);
    }
}