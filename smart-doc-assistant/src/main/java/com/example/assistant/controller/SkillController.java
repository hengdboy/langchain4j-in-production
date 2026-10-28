package com.example.assistant.controller;

import com.example.assistant.assistant.SkillAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ch18 新增：Skills Agent 接口
 *
 * <p>用法：
 * <pre>
 * # 触发 refund-policy 技能（模型先 activate_skill，再查知识库按流程回答）
 * curl -X POST http://localhost:8090/api/skills/ask \
 *   -H "Content-Type: application/json" \
 *   -d '{"sessionId":"1","question":"我买的东西坏了想退货，运费谁出？"}'
 *
 * # 触发 troubleshooting 技能
 * curl -X POST http://localhost:8090/api/skills/ask \
 *   -H "Content-Type: application/json" \
 *   -d '{"sessionId":"2","question":"付款一直失败，怎么办？"}'
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillAgent skillAgent;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        Long sessionId = parseSession(body.get("sessionId"));
        String answer = skillAgent.chat(sessionId, body.getOrDefault("question", "你好")).content();
        return Map.of("success", true, "answer", answer);
    }

    private Long parseSession(String s) {
        try {
            return (s == null || s.isBlank()) ? 1L : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
