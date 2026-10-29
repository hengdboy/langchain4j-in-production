package com.example.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ch09：DashScope 原生多模态 Embedding 客户端。
 *
 * <p>为什么不用 OpenAI 兼容接口？官方文档明确：多模态向量化（tongyi-embedding-vision 系列）
 * 只支持 DashScope 原生 API，OpenAI 兼容接口与控制台均不可用。所以这里用 Java 21 自带的
 * {@link HttpClient} 手动调用原生端点，配合 Spring 自带 Jackson 做 JSON 序列化，
 * <b>零新增依赖</b>（符合轻量化交付偏好）。
 *
 * <p>原生端点：
 * <pre>
 * POST {base-url}/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding
 * </pre>
 *
 * <p>核心概念：
 * <ul>
 * <li>独立向量：一个 content（text 或 image）各出一个向量，type 区分 text/image</li>
 * <li>融合向量：text 与 image 放在同一个 content 对象里，融合为一个向量（type=fused）</li>
 * <li>所有向量处于同一个语义空间，因此可以直接做跨模态检索（文本搜图 / 图搜图）</li>
 * </ul>
 * @author hengdboy
 */
@Slf4j
@Component
public class MultimodalEmbeddingClient {

    private static final String ENDPOINT_PATH =
            "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${chat.multimodal.embedding.api-key}")
    private String apiKey;

    @Value("${chat.multimodal.embedding.base-url}")
    private String baseUrl;

    @Value("${chat.multimodal.embedding.model-name}")
    private String modelName;

    @Value("${chat.multimodal.embedding.dimension:768}")
    private int dimension;

    /** 文本 → 向量（独立向量，type=text） */
    public float[] embedText(String text) {
        return embedSingle(Map.of("text", text));
    }

    /** 图片 → 向量（独立向量，type=image；image 支持公网 URL 或 data:image/xxx;base64,...） */
    public float[] embedImage(String imageUrlOrDataUri) {
        return embedSingle(Map.of("image", imageUrlOrDataUri));
    }

    /** 文本 + 图片 融合 → 单个向量（type=fused，同一对象里同时放 text 与 image） */
    public float[] embedFused(String text, String imageUrl) {
        Map<String, String> fused = new LinkedHashMap<>();
        fused.put("text", text);
        fused.put("image", imageUrl);
        return embedSingle(fused);
    }

    private float[] embedSingle(Map<String, String> content) {
        List<float[]> all = embed(List.of(content));
        return all.isEmpty() ? new float[0] : all.get(0);
    }

    /**
     * 批量嵌入：每个 content 返回一个向量（按 input.contents 顺序一一对应）。
     * 这是原生 API 的最底层封装，embedText / embedImage / embedFused 都复用这里。
     */
    public List<float[]> embed(List<Map<String, String>> contents) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("input", Map.of("contents", contents));
            body.put("parameters", Map.of("dimension", dimension));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + ENDPOINT_PATH))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "多模态 Embedding 调用失败：HTTP " + response.statusCode()
                                + "，响应：" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddings = root.path("output").path("embeddings");

            List<float[]> result = new ArrayList<>();
            for (JsonNode node : embeddings) {
                float[] vector = objectMapper.convertValue(node.get("embedding"), float[].class);
                result.add(vector);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("多模态 Embedding 请求异常：" + e.getMessage(), e);
        }
    }

    /** 当前配置的向量维度（用于启动日志/校验） */
    public int dimension() {
        return dimension;
    }
}
