package com.example.sdr.controller;

import com.example.sdr.service.DemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ch24 API 层：端到端 demo 接口。
 *
 * <p>注入一条询盘 → 看 6 阶段流转（阶段一真实 Agent，阶段二~六 canned + 真实 FSM）。
 *
 * <h2>用法</h2>
 * <pre>
 * # 默认（useRealAgent=true 需要 DASHSCOPE_API_KEY）
 * curl -X POST http://localhost:8091/api/demo/run \
 *   -H "Content-Type: application/json" \
 *   -d '{"email":"buyer@example.com","company":"ACME Corp","country":"US",
 *        "inquiryBody":"Hi, we are ACME and want to order 1000 IoT gateways. Contact our procurement manager sarah@acme.com"}'
 *
 * # 无 key 环境：useRealAgent=false 走 canned 评估
 * curl -X POST http://localhost:8091/api/demo/run \
 *   -H "Content-Type: application/json" \
 *   -d '{"email":"buyer@example.com","company":"ACME Corp","country":"US","useRealAgent":false}'
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoService demoService;

    @PostMapping("/run")
    public DemoService.DemoResult run(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String email = str(b, "email", "buyer@example.com");
        String company = str(b, "company", "ACME Corp");
        String country = str(b, "country", "US");
        String inquiryBody = b.get("inquiryBody") == null ? null : String.valueOf(b.get("inquiryBody"));
        Boolean useRealAgent = b.get("useRealAgent") == null
                ? null
                : Boolean.parseBoolean(String.valueOf(b.get("useRealAgent")));
        return demoService.run(email, company, country, inquiryBody, useRealAgent);
    }

    private String str(Map<String, Object> b, String key, String def) {
        return b.get(key) == null ? def : String.valueOf(b.get(key));
    }
}
