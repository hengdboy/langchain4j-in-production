package com.example.assistant.controller;

import com.example.assistant.service.DocumentSearchService;
import com.example.assistant.service.DocumentSearchService.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Ch08：语义搜索端点。
 *
 * <p>POST /api/search/semantic  —— 文档语义检索
 * 请求：{"query":"...", "topK":3, "minScore":0.5}
 * 响应：{"success":true, "query":"...", "hits":[{score, docId, category, content}, ...]}
 */
@RestController
@RequiredArgsConstructor
public class SemanticSearchController {

    private final DocumentSearchService documentSearchService;

    @PostMapping("/api/search/semantic")
    public Map<String, Object> semanticSearch(@RequestBody SearchRequest req) {
        List<SearchHit> hits = documentSearchService.search(
                req.query(), req.topK() == null ? 3 : req.topK(),
                req.minScore() == null ? 0.6 : req.minScore());
        return Map.of(
                "success", true,
                "query", req.query(),
                "hits", hits
        );
    }

    public record SearchRequest(String query, Integer topK, Double minScore) {}
}
