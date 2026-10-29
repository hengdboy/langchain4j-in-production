package com.example.assistant.controller;

import com.example.assistant.service.VisionAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ch09：图像理解端点。
 *
 * <p>POST /api/vision/analyze —— 图片问答 / OCR
 * 请求（二选一传图）：
 * <pre>
 * { "imageUrl": "https://.../cat.jpg", "question": "图里有什么" }         // URL 传图
 * { "imageBase64": "iVBORw0KGgo...", "mimeType": "image/png", "question": "..." }  // Base64 传图
 * </pre>
 * 响应：{"success": true, "answer": "模型回答"}
 * @author hengdboy
 */
@RestController
@RequiredArgsConstructor
public class VisionController {

    private final VisionAnalysisService visionAnalysisService;

    @PostMapping("/api/vision/analyze")
    public Map<String, Object> analyze(@RequestBody VisionRequest req) {
        String answer;
        if (req.imageUrl() != null && !req.imageUrl().isBlank()) {
            answer = visionAnalysisService.analyzeByUrl(req.imageUrl(), req.question());
        } else if (req.imageBase64() != null && !req.imageBase64().isBlank()) {
            answer = visionAnalysisService.analyzeByBase64(
                    req.imageBase64(),
                    req.mimeType() == null ? "image/png" : req.mimeType(),
                    req.question());
        } else {
            return Map.of("success", false, "error", "imageUrl 与 imageBase64 至少提供一个");
        }
        return Map.of("success", true, "answer", answer);
    }

    public record VisionRequest(String imageUrl, String imageBase64, String mimeType, String question) {}
}
