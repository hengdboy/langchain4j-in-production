package com.example.assistant.controller;

import com.example.assistant.engineering.AgentAuditService;
import com.example.assistant.engineering.EngineeringAgent;
import com.example.assistant.engineering.RequestContext;
import com.example.assistant.guardrail.InputGuard;
import com.example.assistant.guardrail.OutputGuard;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 新增：工程化 Agent 接口
 *
 * <p>用法：
 * <pre>
 * # ① 工程化 Agent（动态工具 + 审计 + 并发 + 可观测）
 * curl -X POST http://localhost:8090/api/agent/engineering/chat \
 * -H "Content-Type: application/json" \
 * -d '{"sessionId":"1","question":"退款政策里运费谁出？再帮我算一下 500 的 8 折是多少"}'
 *
 * # ② 查看该会话的工具调用审计
 * curl "http://localhost:8090/api/agent/audit?sessionId=1"
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent")
public class EngineeringAgentController {

    private final EngineeringAgent engineeringAgent;
    private final AgentAuditService agentAuditService;

    @Resource
    private InputGuard inputGuard;

    @Resource
    private OutputGuard outputGuard;

    @PostMapping("/engineering/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        Long sessionId = parseSession(body.get("sessionId"));
        // ① 从请求体取租户/用户/角色（生产从 JWT/网关头取），写入请求级上下文
        // → DynamicToolProvider 在 provideTools 时据此做租户/角色隔离
        RequestContext.set(
                body.getOrDefault("tenantId", "default"),
                body.getOrDefault("userId", "anonymous"),
                body.getOrDefault("role", "USER"));

        // 输入清洗和安全校验
        inputGuard.sanitize(body.get("question"));
        try {
            String answer = engineeringAgent.chat(sessionId, body.get("question")).content();
            // 输出清洗和安全校验
            String safeAnswer = outputGuard.guard(answer);
            return Map.of("success", true, "answer", safeAnswer);
        } finally {
            // ② 请求结束必须清理 ThreadLocal，防止线程池复用串租户
            RequestContext.clear();
        }
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "1") String sessionId,
                                     @RequestParam(required = false) String tenantId,
                                     @RequestParam(required = false) String userId) {
        // 注意类型一致：审计写入时 key 是 Long（chatMemoryId），查询也必须转 Long
        Long id = parseSession(sessionId);
        // 复合 key 是 tenantId:userId:sessionId；查询 HTTP 请求没有租户上下文（ThreadLocal 已清），
        // 必须显式传参——不传时只能查到 default:anonymous 写入的记录
        List<AgentAuditService.AuditEntry> entries =
                (tenantId != null && userId != null)
                        ? agentAuditService.getAudit(tenantId, userId, id)
                        : agentAuditService.getAudit(id);
        return Map.of("success", true,
                "sessionId", sessionId,
                "key", (tenantId != null && userId != null) ? tenantId + ":" + userId + ":" + id : "default:anonymous:" + id,
                "entries", entries);
    }

    private Long parseSession(String s) {
        try {
            return (s == null || s.isBlank()) ? 1L : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
