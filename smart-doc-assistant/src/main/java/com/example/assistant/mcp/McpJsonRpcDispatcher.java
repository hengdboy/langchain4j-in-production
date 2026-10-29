package com.example.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 深度开发：JSON-RPC 路由器。
 *
 * <p>官方社区 SDK {@link McpServer} 只支持 Tools 原语（{@code initialize} / {@code tools/list} /
 * {@code tools/call}）。本项目通过 {@link McpJsonRpcDispatcher} 包装层，把 Resources 和 Prompts
 * 原语也接入——统一通过 {@link #handle(JsonNode)} 入口分发到对应的提供者。
 *
 * <h2>分发逻辑</h2>
 * <pre>
 * JSON-RPC request.method
 * ├─ initialize / tools/* / ping
 * │     → 委托给 McpServer.handle()（官方 SDK 支持）
 * ├─ resources/list    → McpResourcesProvider.listResources()
 * ├─ resources/read    → McpResourcesProvider.readResource(uri)
 * ├─ prompts/list      → McpPromptsProvider.listPrompts()
 * ├─ prompts/get       → McpPromptsProvider.getPrompt(name, args)
 * └─ 其他               → 返回 JSON-RPC error: "Method not found" (-32601)
 * </pre>
 *
 * <h2>id / arguments 类型保留</h2>
 * <p>JSON-RPC 2.0 允许 {@code id} 为 number/string/null，本项目直接透传 {@link JsonNode}
 * 给 {@link JsonRpcResponse}，避免类型丢失。同时 {@code arguments} 字段也按原始 JSON
 * 类型（number/bool/string/object）传入，不再 {@code asText()} 强转字符串——MCP
 * 客户端可以用布尔开关、数字阈值等结构化参数。
 *
 * @author hengdboy
 * @since v0.17
 */
@Slf4j
public class McpJsonRpcDispatcher {

    private final McpServer mcpServer;
    private final McpResourcesProvider resourcesProvider;
    private final McpPromptsProvider promptsProvider;

    public McpJsonRpcDispatcher(McpServer mcpServer,
                                McpResourcesProvider resourcesProvider,
                                McpPromptsProvider promptsProvider) {
        this.mcpServer = mcpServer;
        this.resourcesProvider = resourcesProvider;
        this.promptsProvider = promptsProvider;
    }

    /**
     * 统一入口：解析 JSON-RPC 请求的 {@code method} 字段，分发到对应处理者。
     *
     * @param request JSON-RPC 请求（含 jsonrpc/id/method/params）
     * @return 响应对象：自定义 {@link JsonRpcResponse}（Resources/Prompts）或 SDK 的
     * {@link McpJsonRpcMessage}（Tools）。Controller 用 Object 接收。
     */
    public Object handle(JsonNode request) {
        // 透传 id 节点（保留 number/string/null 类型）
        JsonNode idNode = request.get("id");
        Object id = (idNode == null || idNode.isNull()) ? null : idNode;

        String method = request.path("method").asText("");
        log.debug("[MCP Dispatcher] method={} id={}", method, idNode);

        // Resources：resources/list 与 resources/read
        if (method.equals("resources/list")) {
            return JsonRpcResponse.success(id, Map.of("resources", resourcesProvider.listResources()));
        }
        if (method.equals("resources/read")) {
            String uri = request.path("params").path("uri").asText("");
            Optional<List<Map<String, Object>>> contents = resourcesProvider.readResource(uri);
            if (contents.isEmpty()) {
                return JsonRpcResponse.error(id, -32602, "Resource not found: " + uri);
            }
            return JsonRpcResponse.success(id, Map.of("contents", contents.get()));
        }

        // Prompts：prompts/list 与 prompts/get
        if (method.equals("prompts/list")) {
            return JsonRpcResponse.success(id, Map.of("prompts", promptsProvider.listPrompts()));
        }
        if (method.equals("prompts/get")) {
            String name = request.path("params").path("name").asText("");
            // arguments 保留原始 JSON 类型（不 asText 强转）
            Map<String, Object> args = parseArgs(request.path("params").path("arguments"));
            Optional<List<Map<String, Object>>> messages = promptsProvider.getPrompt(name, args);
            if (messages.isEmpty()) {
                return JsonRpcResponse.error(id, -32602, "Prompt not found: " + name);
            }
            return JsonRpcResponse.success(id, Map.of("messages", messages.get()));
        }

        // Tools 与其他官方支持的方法（initialize / ping / notifications/...）：委托 SDK
        return mcpServer.handle(request);
    }

    /**
     * 把 arguments 的 JsonNode 转成 Map<String, Object>，保留原始类型。
     * 支持 number / boolean / string / nested object / array；空/缺失返回空 Map。
     */
    private static Map<String, Object> parseArgs(JsonNode argsNode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (argsNode == null || !argsNode.isObject()) {
            return out;
        }
        argsNode.fields().forEachRemaining(e -> out.put(e.getKey(), toJavaValue(e.getValue())));
        return out;
    }

    /** 把 JsonNode 转为 Java 值，保留 number/boolean/array/object 等类型。 */
    private static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt() || node.isLong()) return node.longValue();
        if (node.isFloat() || node.isDouble() || node.isBigDecimal()) return node.doubleValue();
        if (node.isTextual()) return node.textValue();
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            node.elements().forEachRemaining(n -> list.add(toJavaValue(n)));
            return list;
        }
        if (node.isObject()) {
            return parseArgs(node); // 递归
        }
        return node.toString();
    }
}