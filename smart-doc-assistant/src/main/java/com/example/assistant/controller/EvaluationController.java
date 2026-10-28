package com.example.assistant.controller;

import com.example.assistant.service.RagEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ch13 新增：RAG 评估接口
 *
 * <p>用法：
 * <pre>
 * # 仅检索指标（离线，无需 API Key，秒级返回）
 * curl "http://localhost:8090/api/rag/evaluate?includeAnswer=false"
 *
 * # 含端到端答案 + LLM-as-Judge（需 Key，会触发多次 LLM 调用）
 * curl "http://localhost:8090/api/rag/evaluate?includeAnswer=true"
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class EvaluationController {

    private final RagEvaluator ragEvaluator;

    @GetMapping("/evaluate")
    public RagEvaluator.EvaluationReport evaluate(
            @RequestParam(defaultValue = "false") boolean includeAnswer) {
        return ragEvaluator.evaluate(includeAnswer);
    }
}
