package com.example.assistant.controller;

import com.example.assistant.assistant.ResearchAgent;
import com.example.assistant.service.ManualAgentService;
import dev.langchain4j.internal.Json;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ch14 新增：Agent 接口
 *
 * <p>用法：
 * <pre>
 * # 声明式 Agent（@AiService 底层自动跑工具循环，带多轮记忆）
 * curl -X POST http://localhost:8090/api/agent/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"sessionId":"1","question":"退款一般多久到账？再帮我算一下 3 个工作日等于多少小时"}'
 *
 * # 手动循环 Agent（同一组工具，自己实现的循环，便于观察每一步）
 * curl -X POST http://localhost:8090/api/agent/manual \
 *   -H "Content-Type: application/json" \
 *   -d '{"sessionId":"1","question":"现在几点了？帮我算 128 乘以 7 是多少"}'
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent")
public class AgentController {

    private final ResearchAgent researchAgent;
    private final ManualAgentService manualAgentService;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        Long sessionId = parseSession(body.get("sessionId"));
        Result<String> answer = researchAgent.chat(sessionId, body.get("question"));
        List<ToolExecution> toolExecutions = answer.toolExecutions();
        for (ToolExecution toolExecution : toolExecutions) {
            if(Objects.isNull(toolExecution)){
                continue;
            }
            log.info("Tool execution: {}", toolExecution);
        }
        return Map.of("success", true, "answer", answer.content());
    }

    @PostMapping("/manual")
    public Map<String, Object> manual(@RequestBody Map<String, String> body) {
        String answer = manualAgentService.run(body.get("sessionId"), body.get("question"));
        return Map.of("success", true, "answer", answer);
    }

    private Long parseSession(String s) {
        try {
            return (s == null || s.isBlank()) ? 1L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
