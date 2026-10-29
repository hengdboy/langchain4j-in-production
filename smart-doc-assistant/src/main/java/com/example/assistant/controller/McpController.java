package com.example.assistant.controller;

import com.example.assistant.assistant.McpAgent;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 新增：MCP Agent 接口（工具 + 资源两种原语）
 *
 * <p>用法：
 * <pre>
 * 工具原语：问 Agent，让它调用 MCP 服务器暴露的工具
 * curl -X POST http://localhost:8090/api/mcp/ask \
 * -H "Content-Type: application/json" -d '{"question":"3 加 4 等于多少？"}'
 *
 * 资源原语：列出 MCP 服务器上的资源
 * curl http://localhost:8090/api/mcp/resources
 *
 * 资源原语：读取指定资源内容
 * curl "http://localhost:8090/api/mcp/resource?uri=docs://demo/protocol-notes"
 * </pre>
 *
 * <p>本控制器依赖 McpAgent / McpClient，故与 {@link com.example.assistant.config.McpConfig}
 * 共用同一开关：{@code chat.mcp.enabled=false} 时整个 MCP 模块（含本控制器）不注册，
 * 应用其余功能不受影响。
 * @author hengdboy
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name = "chat.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpController {

    private final McpAgent mcpAgent;
    private final McpClient mcpClient;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String answer = mcpAgent.ask(body.getOrDefault("question", "你好"));
        return Map.of("success", true, "answer", answer);
    }

    /** 资源发现：McpClient.listResources()（源码核实） */
    @GetMapping("/resources")
    public Map<String, Object> resources() {
        List<McpResource> resources = mcpClient.listResources();
        return Map.of("success", true,
                "resources", resources.stream()
                        .map(r -> Map.of("uri", String.valueOf(r.uri()),
                                "name", String.valueOf(r.name()),
                                "mimeType", String.valueOf(r.mimeType())))
                        .toList());
    }

    /** 资源读取：McpClient.readResource(uri)（源码核实） */
    @GetMapping("/resource")
    public Map<String, Object> resource(@RequestParam String uri) {
        McpReadResourceResult result = mcpClient.readResource(uri);
        // 手动取字段：uri()/text()/mimeType() 在子类 McpTextResourceContents 上（基类是多态接口）
        return Map.of("success", true, "contents", result.contents().stream()
                .map(c -> c instanceof McpTextResourceContents t
                        ? Map.of("uri", t.uri(), "mimeType", t.mimeType(), "text", t.text())
                        : Map.of("raw", String.valueOf(c)))
                .toList());
    }

    /** 提示发现：McpClient.listPrompts()（源码核实） */
    @GetMapping("/prompts")
    public Map<String, Object> prompts() {
        // 手动取字段：McpPrompt 无 Bean getter，直接序列化会报 No serializer found
        return Map.of("success", true, "prompts", mcpClient.listPrompts().stream()
                .map(p -> Map.of(
                        "name", p.name(),
                        "description", String.valueOf(p.description()),
                        "arguments", p.arguments() == null ? List.of() : p.arguments().stream()
                                .map(a -> Map.of("name", a.name(),
                                        "description", String.valueOf(a.description())))
                                .toList()))
                .toList());
    }

    /** 提示获取：McpClient.getPrompt(name, args)（源码核实） */
    @GetMapping("/prompt")
    public Map<String, Object> prompt(@RequestParam String name,
                                      @RequestParam(defaultValue = "general") String issue) {
        McpGetPromptResult r = mcpClient.getPrompt(name, Map.of("issue", issue));
        return Map.of("success", true,
                "description", String.valueOf(r.description()),
                "messages", r.messages() == null ? List.of() : r.messages().stream()
                        .map(m -> {
                            dev.langchain4j.data.message.Content c = m.content().toContent();
                            String text = c instanceof dev.langchain4j.data.message.TextContent tc
                                    ? tc.text() : String.valueOf(c);
                            return Map.of("role", String.valueOf(m.role()), "text", text);
                        })
                        .toList());
    }
}
