package com.example.assistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch19：JSON-RPC 路由器分支。
 *
 * <p>只覆盖 Resources / Prompts 两条自实现分支（Tools 交给官方 SDK，单独依赖检索 Bean，
 * 不在单测里拉起来）。{@code mcpServer} 传 null，确保这些分支不会误委托出去。
 *
 * @author hengdboy
 */
@DisplayName("McpJsonRpcDispatcher：resources / prompts 分支")
class McpJsonRpcDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpJsonRpcDispatcher dispatcher =
            new McpJsonRpcDispatcher(null, new McpResourcesProvider(), new McpPromptsProvider());

    @Test
    @DisplayName("resources/list 返回 classpath 文档清单")
    @SuppressWarnings("unchecked")
    void resourcesList() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list"}
                """);

        assertThat(response.error).isNull();
        List<Map<String, Object>> resources =
                (List<Map<String, Object>>) response.result.get("resources");
        assertThat(resources).extracting(r -> r.get("uri"))
                .contains("docs://product-faq.txt", "docs://refund-policy.txt",
                        "docs://technical-guide.md", "docs://report.pdf");
        assertThat(resources).allSatisfy(r -> assertThat(r)
                .containsKeys("uri", "name", "mimeType"));
    }

    @Test
    @DisplayName("resources/read：文本返回 text，PDF 返回 blob")
    @SuppressWarnings("unchecked")
    void resourcesRead() throws Exception {
        JsonRpcResponse text = handle("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"docs://refund-policy.txt"}}
                """);
        Map<String, Object> textContent =
                ((List<Map<String, Object>>) text.result.get("contents")).get(0);
        assertThat(textContent).containsKey("text");
        assertThat((String) textContent.get("text")).isNotBlank();

        JsonRpcResponse pdf = handle("""
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"docs://report.pdf"}}
                """);
        Map<String, Object> pdfContent =
                ((List<Map<String, Object>>) pdf.result.get("contents")).get(0);
        assertThat(pdfContent).containsKey("blob");
        assertThat((String) pdfContent.get("blob")).isNotBlank();
    }

    @Test
    @DisplayName("未知资源返回 -32602")
    void unknownResource() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"docs://nope.txt"}}
                """);

        assertThat(response.result).isNull();
        assertThat(response.error.code).isEqualTo(-32602);
        assertThat(response.error.message).contains("docs://nope.txt");
    }

    @Test
    @DisplayName("prompts/list 暴露 3 个模板")
    @SuppressWarnings("unchecked")
    void promptsList() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":5,"method":"prompts/list"}
                """);

        List<Map<String, Object>> prompts =
                (List<Map<String, Object>>) response.result.get("prompts");
        assertThat(prompts).extracting(p -> p.get("name"))
                .containsExactlyInAnyOrder("review-code", "summarize-doc", "qa-policy-check");
    }

    @Test
    @DisplayName("prompts/get 填充占位符，不留 {{var}}")
    @SuppressWarnings("unchecked")
    void promptsGetRendersArguments() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":6,"method":"prompts/get","params":{
                  "name":"summarize-doc",
                  "arguments":{"document":"退货政策全文","max_words":50,"language":"中文"}}}
                """);

        Map<String, Object> message =
                ((List<Map<String, Object>>) response.result.get("messages")).get(0);
        Map<String, Object> content = (Map<String, Object>) message.get("content");
        String rendered = (String) content.get("text");
        assertThat(rendered).contains("50", "中文", "退货政策全文").doesNotContain("{{");
    }

    @Test
    @DisplayName("未知 Prompt 返回 -32602")
    void unknownPrompt() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":7,"method":"prompts/get","params":{"name":"nope"}}
                """);

        assertThat(response.result).isNull();
        assertThat(response.error.code).isEqualTo(-32602);
    }

    @Test
    @DisplayName("字符串 id 原样透传，不被强转")
    void stringIdPreserved() throws Exception {
        JsonRpcResponse response = handle("""
                {"jsonrpc":"2.0","id":"req-abc","method":"prompts/list"}
                """);

        assertThat(response.id).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) response.id).asText()).isEqualTo("req-abc");
    }

    private JsonRpcResponse handle(String json) throws Exception {
        return (JsonRpcResponse) dispatcher.handle(MAPPER.readTree(json));
    }
}
