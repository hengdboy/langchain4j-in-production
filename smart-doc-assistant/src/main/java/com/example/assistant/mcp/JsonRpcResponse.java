package com.example.assistant.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * MCP JSON-RPC 2.0 统一响应结构。
 *
 * <p>官方 SDK 的 {@code McpJsonRpcMessage} 是个轻量 POJO，只带 {@code jsonrpc} 和 {@code id}，
 * 真正的 {@code result}/{@code error} 字段依赖 Jackson 的运行时 magic。本类做更朴素的事：
 * 显式字段 + 显式构造，更易读、易测、易扩展（resources/prompts）。
 *
 * <h2>id 类型说明</h2>
 * <p>JSON-RPC 2.0 规范允许 id 是 <strong>number / string / null</strong> 三种类型；
 * 用 {@code Object} 承载原值，Jackson 序列化时按原始类型输出，避免客户端收到
 * 数字 id 被强转字符串（或反过来）导致请求-响应无法关联。
 *
 * <h2>两种构造</h2>
 * <ul>
 * <li>{@link #success(Object, Map)}：正常响应（{@code result} 字段）</li>
 * <li>{@link #error(Object, int, String)}：错误响应（{@code error.code} + {@code error.message}）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    public final String jsonrpc = "2.0";
    public final Object id;
    public final Map<String, Object> result;   // null 表示错误响应
    public final ErrorBody error;              // null 表示正常响应

    private JsonRpcResponse(Object id, Map<String, Object> result, ErrorBody error) {
        this.id = id;
        this.result = result;
        this.error = error;
    }

    public static JsonRpcResponse success(Object id, Map<String, Object> result) {
        return new JsonRpcResponse(id, result, null);
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        return new JsonRpcResponse(id, null, new ErrorBody(code, message));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorBody {
        public final int code;
        public final String message;

        public ErrorBody(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}