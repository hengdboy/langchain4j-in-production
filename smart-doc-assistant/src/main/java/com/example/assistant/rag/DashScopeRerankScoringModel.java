package com.example.assistant.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Ch12 升级：基于阿里百炼 {@code qwen3-rerank} 的 cross-encoder 重排序器。
 *
 * <p>替换原有的 {@link EmbeddingScoringModel}（bi-encoder 余弦相似度），改用真正的
 * 交叉编码器模型对 (query, document) 联合打分，效果显著优于"再算一遍余弦"。
 *
 * <h3>API 规格（百炼兼容 OpenAI rerank 格式）</h3>
 * <ul>
 *   <li>端点：{@code POST {workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks}</li>
 *   <li>认证：{@code Authorization: Bearer {apiKey}}</li>
 *   <li>请求体：扁平结构 {@code {model, query, documents[], top_n?, instruct?}}</li>
 *   <li>返回体：{@code {results: [{index, relevance_score}]}}</li>
 * </ul>
 *
 * <h3>与 EmbeddingScoringModel 的对比</h3>
 * <table>
 *   <tr><th></th><th>EmbeddingScoringModel</th><th>DashScopeRerankScoringModel</th></tr>
 *   <tr><td>类型</td><td>bi-encoder（双编码器）</td><td>cross-encoder（交叉编码器）</td></tr>
 *   <tr><td>原理</td><td>query/doc 分别 embed → 余弦</td><td>query+doc 拼接过一次模型</td></tr>
 *   <tr><td>精度</td><td>一般（≈召回重复计算）</td><td>高（捕捉细粒度交互）</td></tr>
 *   <tr><td>成本</td><td>零（复用本地 embedding）</td><td>0.5 元/百万 token</td></tr>
 * </table>
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/text-rerank-api">通用文本排序模型 API 文档</a>
 */
public class DashScopeRerankScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankScoringModel.class);

    private static final String MODEL = "qwen3-rerank";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final int topN;
    private final HttpClient httpClient;

    /**
     * @param apiKey   百炼 API Key
     * @param baseUrl  完整端点 URL（含 workspaceId），例如：
     *                 {@code https://{workspaceId}.cn-beijing.maas.aliyuncs.com/compatible-api/v1/reranks}
     * @param topN     返回前 N 个结果（≤ 候选文档数）
     */
    public DashScopeRerankScoringModel(String apiKey, String baseUrl, int topN) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("DashScope rerank API key must not be empty");
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("DashScope rerank base URL must not be empty");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.topN = Math.max(1, topN);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("Query must not be empty for reranking");
        }
        try {
            // 1. 构造请求体（OpenAI 兼容 rerank 扁平格式）
            List<String> documents = segments.stream()
                    .map(TextSegment::text)
                    .toList();

            String requestBody = buildRequestBody(query, documents);
            log.info("[Rerank] request: model={}, docs={}, top_n={}", MODEL, documents.size(), topN);

            // 2. 发送 HTTP 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[Rerank] HTTP error status={}: {}", response.statusCode(), response.body());
                throw new RuntimeException(
                        "DashScope rerank API returned HTTP " + response.statusCode() + ": " + response.body());
            }

            // 3. 解析返回：按 index 映射回原始顺序，未命中的文档给 0 分
            return parseRerankResponse(response.body(), segments.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Rerank request interrupted", e);
        } catch (Exception e) {
            log.error("[Rerank] request failed", e);
            throw new RuntimeException("Failed to call DashScope rerank API: " + e.getMessage(), e);
        }
    }

    /** 构建 qwen3-rerank 的 OpenAI 兼容请求 JSON。 */
    private String buildRequestBody(String query, List<String> documents) throws Exception {
        var root = OBJECT_MAPPER.createObjectNode();
        root.put("model", MODEL);
        root.put("query", query);
        root.set("documents", OBJECT_MAPPER.valueToTree(documents));
        root.put("topN", Math.min(topN, documents.size()));
        // instruct 可选：自定义排序任务指令，不设则用模型默认行为
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    /**
     * 解析 rerank 返回，映射为与输入 segments 等长的分数列表。
     *
     * <p>返回示例：{@code {"results":[{"index":2,"relevance_score":0.93},{"index":0,"relevance_score":0.34}]}}
     * <br>→ 把 results[index] → score 数组对应位置，未出现在 results 中的文档分数为 0。
     */
    private Response<List<Double>> parseRerankResponse(String responseBody, int totalSegments) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        JsonNode results = root.get("results");

        double[] scores = new double[totalSegments]; // 默认全 0

        if (results != null && results.isArray()) {
            for (JsonNode result : results) {
                int idx = result.get("index").asInt();
                double score = result.get("relevance_score").asDouble();
                if (idx >= 0 && idx < totalSegments) {
                    scores[idx] = score;
                }
            }
        }

        List<Double> scoreList = new ArrayList<>(totalSegments);
        for (double s : scores) {
            scoreList.add(s);
        }

        log.debug("[Rerank] response: returned {} ranked / {} total, scores={}",
                results != null ? results.size() : 0, totalSegments, scoreList);
        return Response.from(scoreList);
    }
}
