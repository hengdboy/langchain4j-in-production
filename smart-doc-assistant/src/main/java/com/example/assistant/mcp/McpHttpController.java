package com.example.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 深度开发：Streamable HTTP 传输端点。
 *
 * <p>把官方 SDK 的 {@link McpJsonRpcMessage} 处理能力与本项目自实现的 Resources / Prompts
 * 原语统一通过 {@link McpJsonRpcDispatcher} 暴露，对外只提供一个 POST 端点。
 *
 * <h2>与 stdio 传输的对比</h2>
 * <table>
 * <tr><th>维度</th><th>stdio</th><th>HTTP（本项目实现）</th></tr>
 * <tr><td>协议载体</td><td>stdin/stdout JSON-RPC</td><td>HTTP POST + JSON body</td></tr>
 * <tr><td>进程模型</td><td>客户端拉起的子进程</td><td>服务端长期运行的 HTTP 服务</td></tr>
 * <tr><td>调用方式</td><td>每行一条 JSON-RPC</td><td>每个 HTTP body 一条</td></tr>
 * <tr><td>鉴权</td><td>无标准（靠环境变量）</td><td>Header（Bearer / API Key）</td></tr>
 * <tr><td>日志</td><td>必须走 stderr</td><td>正常走 stdout</td></tr>
 * </table>
 *
 * <h2>端点协议</h2>
 * <pre>
 * POST /mcp
 * Content-Type: application/json
 * Body: {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
 * → 200 OK
 * {"jsonrpc":"2.0","id":1,"result":{...}}
 * </pre>
 *
 * @author hengdboy
 * @since v0.17
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chat.mcp.server.enabled", havingValue = "true")
public class McpHttpController {

    private final McpJsonRpcDispatcher dispatcher;
    private final McpAuditService auditService;
    private final McpSecurityService securityService;

    /**
     * Streamable HTTP 传输的核心端点：接收 JSON-RPC 请求 → 返回 JSON-RPC 响应。
     *
     * <p>{@link McpJsonRpcDispatcher#handle(JsonNode)} 是协议无关的——stdio 传输也是调它，
     * HTTP 传输也是调它。框架把"消息解析"和"传输层"解耦。
     *
     * <p>返回值类型为 {@code Object}：Tools 分支返回 SDK 的 {@link McpJsonRpcMessage}，
     * Resources/Prompts 分支返回本项目的 {@link JsonRpcResponse}，Jackson 自动按各自字段序列化。
     *
     * @param request     客户端发送的 JSON-RPC 请求
     * @param authHeader  {@code Authorization} 头（Bearer <key>）
     * @param apiKeyHeader {@code X-MCP-API-Key} 头（备选鉴权方式）
     * @return JSON-RPC 响应（{@code result} 或 {@code error}）
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(@RequestBody JsonNode request,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                          @RequestHeader(value = "X-MCP-API-Key", required = false) String apiKeyHeader) {
        long start = System.currentTimeMillis();
        String method = request.path("method").asText("");
        // 透传 id 节点，保留 number/string/null 类型
        JsonNode rawId = request.get("id");
        Object id = (rawId == null || rawId.isNull()) ? null : rawId;

        log.info("[MCP HTTP] method={} id={}", method, rawId);

        // 鉴权（initialize 与 ping 是握手阶段，允许匿名通过；其他方法必须鉴权）
        if (!method.equals("initialize") && !method.equals("ping") && !method.equals("notifications/initialized")) {
            if (!securityService.isAuthorized(authHeader, apiKeyHeader)) {
                log.warn("[MCP HTTP] unauthorized request, method={}", method);
                return ResponseEntity.status(401)
                        .body(JsonRpcResponse.error(id, -32001, "Unauthorized: invalid or missing API key"));
            }
            // 工具白名单检查
            if (method.equals("tools/call")) {
                String toolName = request.path("params").path("name").asText("");
                if (!securityService.isToolAllowed(toolName)) {
                    log.warn("[MCP HTTP] tool not in whitelist: {}", toolName);
                    return ResponseEntity.status(403)
                            .body(JsonRpcResponse.error(id, -32601,
                                    "Tool not allowed: " + toolName + " (allowed: " + securityService.allowedToolList() + ")"));
                }
            }
        }

        Object response;
        Throwable error = null;
        try {
            response = dispatcher.handle(request);
        } catch (Exception e) {
            log.error("[MCP HTTP] dispatcher error: {}", e.getMessage(), e);
            error = e;
            response = JsonRpcResponse.error(id, -32603, "Internal error: " + e.getMessage());
        }

        long duration = System.currentTimeMillis() - start;

        // 审计：只对 tools/call 留痕；其他方法（initialize/list/read）噪声太大
        if (method.equals("tools/call")) {
            String toolName = request.path("params").path("name").asText("");
            auditService.record(toolName, request.path("params").path("arguments").toString(),
                    duration, error != null);
        }

        log.info("[MCP HTTP] response id={} duration={}ms", rawId, duration);
        return ResponseEntity.ok(response);
    }

    /**
     * 查看最近的工具调用审计记录。
     * GET /mcp/audit?n=10
     */
    @GetMapping("/audit")
    public java.util.Map<String, Object> recentAudit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestHeader(value = "X-MCP-API-Key", required = false) String apiKeyHeader,
            @RequestParam(defaultValue = "20") int n) {
        if (!securityService.isAuthorized(authHeader, apiKeyHeader)) {
            return java.util.Map.of("error", "Unauthorized");
        }
        return java.util.Map.of("entries", auditService.recent(n), "count", n);
    }
}