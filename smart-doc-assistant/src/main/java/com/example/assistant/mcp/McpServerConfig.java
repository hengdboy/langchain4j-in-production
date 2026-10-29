package com.example.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 深度开发：Spring 上下文下的 MCP Server 装配。
 *
 * <p>与 {@link McpServerStarter}（独立进程、stdio 传输）不同，本类让 MCP Server 作为
 * Spring Bean 注入到现有的 smart-doc-assistant Web 应用中，通过 HTTP 端点暴露。
 *
 * <h2>为什么不直接用 stdio</h2>
 * <ul>
 * <li>stdio 需要独立进程，由 MCP 客户端启动；本应用作为 Web 服务运行时，
 * 复用 Spring 上下文直接暴露 HTTP 端点更轻量</li>
 * <li>HTTP 传输（MCP 2025-03+ 标准的 Streamable HTTP）支持远程访问，
 * 与 stdio 互为补充：本地调试用 stdio，生产远程调用用 HTTP</li>
 * </ul>
 *
 * <h2>Bean 复用</h2>
 * <p>工具 Bean {@link KnowledgeBaseMcpTools} 直接复用 / 的
 * {@link EmbeddingModel} / {@link EmbeddingStore} / {@link RetrievalAugmentor}，
 * 零代码重复——MCP Server 只是这些能力的一个新出口。构造逻辑统一走
 * {@link McpServerFactory#create}，stdio 启动器与 HTTP 模式行为完全一致。
 *
 * @author hengdboy
 * @since v0.17
 */
@Configuration
public class McpServerConfig {

    /**
     * 装配 MCP Server Bean（含 {@link KnowledgeBaseMcpTools#TOOL_NAMES} 三个 @Tool 方法）。
     */
    @Bean
    public McpServer mcpServer(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> embeddingStore,
                               RetrievalAugmentor retrievalAugmentor) {
        return McpServerFactory.create(embeddingModel, embeddingStore, retrievalAugmentor);
    }

    /** Resources 原语：扫描 classpath:docs/ 暴露只读文档。 */
    @Bean
    public McpResourcesProvider mcpResourcesProvider() {
        return new McpResourcesProvider();
    }

    /** Prompts 原语：3 个预定义模板。 */
    @Bean
    public McpPromptsProvider mcpPromptsProvider() {
        return new McpPromptsProvider();
    }

    /**
     * JSON-RPC 路由器：包装 McpServer + Resources + Prompts，
     * 统一通过 {@link McpJsonRpcDispatcher#handle(JsonNode)} 分发。
     */
    @Bean
    public McpJsonRpcDispatcher mcpJsonRpcDispatcher(McpServer mcpServer,
                                                     McpResourcesProvider mcpResourcesProvider,
                                                     McpPromptsProvider mcpPromptsProvider) {
        return new McpJsonRpcDispatcher(mcpServer, mcpResourcesProvider, mcpPromptsProvider);
    }
}