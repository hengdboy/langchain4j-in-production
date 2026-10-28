package com.example.assistant.controller;

import com.example.assistant.service.MultimodalSearchService;
import com.example.assistant.service.MultimodalSearchService.ImageHit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ：多模态（跨模态）语义检索端点。
 *
 * <p>POST /api/search/multimodal —— 文本搜图 / 图搜图
 * 请求：
 * <pre>
 * // 文本搜图：用一句话找到语义最接近的图片
 * { "mode": "text", "query": "一只猫", "topK": 3 }
 *
 * // 图搜图：用一张图片找到相似的图片
 * { "mode": "image", "imageUrl": "https://.../query.jpg", "topK": 3 }
 * </pre>
 * 响应：{"success": true, "mode": "...", "hits": [{score, id, imageUrl, description}, ...]}
 */
@RestController
@RequiredArgsConstructor
public class MultimodalSearchController {

    private final MultimodalSearchService multimodalSearchService;

    @PostMapping("/api/search/multimodal")
    public Map<String, Object> multimodalSearch(@RequestBody MultimodalSearchRequest req) {
        int topK = req.topK() == null ? 3 : req.topK();
        List<ImageHit> hits;
        String mode = req.mode() == null ? "text" : req.mode();

        if ("image".equals(mode)) {
            if (req.imageUrl() == null || req.imageUrl().isBlank()) {
                return Map.of("success", false, "error", "mode=image 时 imageUrl 必填");
            }
            hits = multimodalSearchService.searchByImage(req.imageUrl(), topK);
        } else {
            if (req.query() == null || req.query().isBlank()) {
                return Map.of("success", false, "error", "mode=text 时 query 必填");
            }
            hits = multimodalSearchService.searchByText(req.query(), topK);
        }
        return Map.of("success", true, "mode", mode, "hits", hits);
    }

    public record MultimodalSearchRequest(String mode, String query, String imageUrl, Integer topK) {}
}
