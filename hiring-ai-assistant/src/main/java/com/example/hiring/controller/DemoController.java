package com.example.hiring.controller;

import com.example.hiring.service.DemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * API 层：端到端 demo 接口。
 *
 * <h2>用法</h2>
 * <pre>
 * # 真实 LLM（需要 api key）
 * curl -X POST http://localhost:8091/api/demo/run -H "Content-Type: application/json" -d '{}'
 * # 无 key 环境：canned 确定性规则跑全流程
 * curl -X POST http://localhost:8091/api/demo/run -H "Content-Type: application/json" \
 * -d '{"useRealAgent": false}'
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoService demoService;

    @PostMapping("/run")
    public DemoService.DemoResult run(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String useRealAgent = b.get("useRealAgent") == null
                ? null : String.valueOf(b.get("useRealAgent"));
        return demoService.run(useRealAgent);
    }
}
