package com.example.assistant.controller;

import com.example.assistant.assistant.DocQaAssistant;
import dev.langchain4j.internal.Json;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ch12：高级 RAG 问答接口（v0.10）。
 *
 * <p>POST /api/rag/ask  {"question": "..."}  → 走父子分块 + 混合检索 + 重排序后交给 LLM 作答。
 * 检索链路离线可跑；最终 LLM 调用需要有效的 chat model Key（dummy key 时返回错误，不影响启动）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagController {

    private final DocQaAssistant docQaAssistant;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return Map.of("success", false, "error", "question 不能为空");
        }
        try {
            Result<String> answer = docQaAssistant.ask(question);

            List<Content> sources = answer.sources();
            log.info("回答来源sources: {}", Json.toJson(sources));
            return Map.of("success", true, "answer", answer.content());
        } catch (Exception e) {
            log.error("❌ RAG 问答失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

}
