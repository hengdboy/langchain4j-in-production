package com.example.assistant.controller;

import com.example.assistant.service.DocumentIngestionService;
import com.example.assistant.service.DocumentIngestionService.PipelineStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Ch10：文档处理流水线端点。
 *
 * <ul>
 * <li>GET  /api/docs/stats            —— 流水线统计（文档数/分块数/维度/分块参数）</li>
 * <li>POST /api/docs/ingest           —— 手动触发目录入库，rebuild=true 时先清库重建</li>
 * <li>POST /api/docs/upload           —— 上传单个文件，流式解析直接入库（不落盘）</li>
 * </ul>
 *
 * <p>配合 POST /api/search/semantic 使用：先入库，再检索。
 * 示例：
 * <pre>
 * curl -X POST 'http://localhost:8090/api/docs/ingest?dir=/tmp/docs&amp;rebuild=false'
 * curl -X POST -F "file=@report.pdf" http://localhost:8090/api/docs/upload
 * </pre>
 * @author hengdboy
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    /** 流水线统计 */
    @GetMapping("/api/docs/stats")
    public Map<String, Object> stats() {
        PipelineStats stats = ingestionService.stats();
        return Map.of(
                "success", true,
                "stats", stats
        );
    }

    /** 手动触发目录入库；rebuild=true 时先清空向量库再重建 */
    @PostMapping("/api/docs/ingest")
    public Map<String, Object> ingestFromDirectory(
            @RequestParam(defaultValue = "./docs") String dir,
            @RequestParam(defaultValue = "false") boolean rebuild) {
        if (rebuild) {
            ingestionService.clearStore();
        }
        ingestionService.ingestFromDirectory(dir);
        PipelineStats stats = ingestionService.stats();
        return Map.of(
                "success", true,
                "message", rebuild ? "已清库并重建" : "增量入库完成",
                "dir", dir,
                "stats", stats
        );
    }

    /** 上传文件直接入库（不落盘，适合小文件即时入库） */
    @PostMapping("/api/docs/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String fileName = file.getOriginalFilename();
        ingestionService.ingestUpload(file);
        PipelineStats stats = ingestionService.stats();
        return Map.of(
                "success", true,
                "message", "文件已入库: " + fileName,
                "stats", stats
        );
    }
}
