package com.example.assistant.mcp;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 深度开发：Resource 原语提供者，把 classpath:docs/* 暴露为 MCP resources。
 *
 * <p>MCP Resources 原语（ 节讲过协议、 端到端用过）让服务器暴露"只读数据"——
 * 文档、图片、配置。客户端通过 {@code resources/list} 发现 URI 清单，{@code resources/read}
 * 按 URI 读取内容。
 *
 * <h2>URI 设计</h2>
 * <p>用 {@code docs://<filename>} 协议——类比 {@code file://}、{@code https://}，
 * 客户端从 URI 字符串就能识别这是本地文档资源。文件实际读 classpath:docs/<filename>。
 *
 * <h2>PDF 等二进制资源的 blob 格式（P2-3 修复）</h2>
 * <p>PDF / 图片等二进制内容用 MCP 标准的 blob 格式返回：{@code {"_meta":..., "blob": "<base64>"}}，
 * 文本文件用 {@code {"text": "..."}}。客户端按 mimeType 决定如何解析。
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
@Slf4j
public class McpResourcesProvider {

    private static final String DOCS_PATH = "docs/";
    private static final String URI_SCHEME = "docs://";

    /** 启动时扫描一次：缓存 (uri → metadata + bytes)。 */
    private final Map<String, ResourceEntry> resources = new LinkedHashMap<>();

    public McpResourcesProvider() {
        loadAll();
    }

    /** 扫描 classpath:docs/ 下的所有可读文件，构建资源清单。 */
    private void loadAll() {
        // 注：教学版硬编码文件名清单——DocumentIngestionService 也是硬编码；
        // 生产应改成 ClassGraph 等类扫描工具自动枚举，避免双真相源。
        for (String name : List.of("product-faq.txt", "refund-policy.txt", "technical-guide.md", "report.pdf")) {
            String uri = URI_SCHEME + name;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(DOCS_PATH + name)) {
                if (in == null) {
                    log.warn(" Resource not found: {}", DOCS_PATH + name);
                    continue;
                }
                byte[] bytes = in.readAllBytes();
                String mime = detectMime(name);
                resources.put(uri, new ResourceEntry(uri, name, mime, bytes));
                log.info("Loaded resource: {} ({} bytes, mime={})", uri, bytes.length, mime);
            } catch (IOException e) {
                log.error("Failed to load resource {}: {}", name, e.getMessage());
            }
        }
    }

    private static String detectMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".csv")) return "text/csv";
        return "text/plain; charset=utf-8";
    }

    /**
     * 对应 MCP {@code resources/list} 请求。
     * @return JSON-RPC result.resources：Resource 数组（每个含 uri / name / mimeType）
     */
    public List<Map<String, Object>> listResources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResourceEntry r : resources.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", r.uri);
            m.put("name", r.name);
            m.put("mimeType", r.mime);
            out.add(m);
        }
        return out;
    }

    /**
     * 对应 MCP {@code resources/read} 请求。
     *
     * <p>内容编码遵循 MCP 规范：
     * <ul>
     * <li>文本类（text/markdown / text/plain / text/html / text/csv / application/json）：
     * 返回 {@code text} 字段（UTF-8 解码）</li>
     * <li>二进制类（application/pdf / image/* 等）：返回 {@code blob} 字段（base64）</li>
     * </ul>
     *
     * @param uri 客户端请求的 URI（如 {@code docs://refund-policy.txt}）
     * @return Optional 内容：URI 不存在时为空；存在则返回 contents 数组
     */
    public Optional<List<Map<String, Object>>> readResource(String uri) {
        ResourceEntry entry = resources.get(uri);
        if (entry == null) {
            return Optional.empty();
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", entry.uri);
        content.put("mimeType", entry.mime);
        if (entry.mime.startsWith("text/") || entry.mime.startsWith("application/json")) {
            content.put("text", new String(entry.bytes, StandardCharsets.UTF_8));
        } else {
            content.put("blob", Base64.getEncoder().encodeToString(entry.bytes));
        }
        return Optional.of(List.of(content));
    }

    /** 内部记录：uri / 文件名 / MIME / 原始字节。 */
    private record ResourceEntry(String uri, String name, String mime, byte[] bytes) {}
}