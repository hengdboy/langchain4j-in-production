package com.example.assistant.engineering;

import com.example.assistant.engineering.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新增：Agent 工具调用审计
 *
 * <p>生产环境必须知道"Agent 刚才到底调了哪些工具、参数是什么、结果如何、有没有失败"——
 * 这是排障、成本核算、安全审计的基础。
 *
 * <p>通过 {@code AiServices.afterToolExecution(Consumer&lt;ToolExecution&gt;)} 回调接入
 * （源码核实：ToolExecution 有 request()/result()/hasFailed()/startTime()）。
 * 这里把每次调用记录到内存（教学版；生产写日志/DB/OpenTelemetry）。
 *
 * <p><b>复合审计 key</b>：生产多租户环境下，审计必须按 {@code tenantId:userId:sessionId}
 * 维度聚合，才能"按租户查调用、按用户追责、按会话排障"。单用 sessionId 无法做租户级合规排查。
 * 复合 key 在记录时由 {@link #auditKey(Object)} 从 {@link RequestContext} 拼出
 * （tool 执行与主调用同线程，{@code RequestContext} 可读到当前租户/用户）。
 * @author hengdboy
 */
@Service
@Slf4j
public class AgentAuditService {

    /** 复合 key (tenantId:userId:sessionId) → 工具调用记录（时间正序） */
    private final Map<String, List<AuditEntry>> auditLog = new ConcurrentHashMap<>();

    public record AuditEntry(String toolName, String arguments, String result,
                             boolean failed, LocalDateTime startTime, LocalDateTime endTime) {}

    /**
     * 拼复合审计 key：tenantId:userId:sessionId
     * sessionId 来自 AiServices 的 chatMemoryId；tenant/user 来自 RequestContext（同线程请求级上下文）。
     */
    public String auditKey(Object sessionId) {
        String tenant = RequestContext.tenantId();
        String user = RequestContext.userId();
        return tenant + ":" + user + ":" + sessionId;
    }

    public void record(Object memoryId, String toolName, String arguments, String result,
                       boolean failed, LocalDateTime startTime) {
        record(memoryId, RequestContext.tenantId(), RequestContext.userId(),
                toolName, arguments, result, failed, startTime);
    }

    /**
     * 显式租户/用户版本的记录入口——审计回调可能跑在虚拟线程上（executeToolsConcurrently），
     * ThreadLocal 里没有请求上下文，租户/用户必须由调用方从请求线程捕获的快照传入。
     */
    public void record(Object memoryId, String tenantId, String userId, String toolName,
                       String arguments, String result, boolean failed, LocalDateTime startTime) {
        String key = tenantId + ":" + userId + ":" + memoryId;
        AuditEntry entry = new AuditEntry(toolName, arguments,
                result != null && result.length() > 200 ? result.substring(0, 200) + "…" : result,
                failed, startTime, LocalDateTime.now());
        auditLog.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        log.info("[审计] 租户={} 用户={} 会话={} 工具[{}] failed={} 耗时{}ms",
                tenantId, userId, memoryId, toolName, failed,
                java.time.Duration.between(startTime, entry.endTime()).toMillis());
    }

    /** 按会话查（兼容旧用法，key 仍含租户/用户维度） */
    public List<AuditEntry> getAudit(Object memoryId) {
        return auditLog.getOrDefault(auditKey(memoryId), List.of());
    }

    /**
     * 按显式租户/用户/会话三元组查。
     *
     * <p>修复记录：查询 HTTP 请求本身不在工具调用线程上，{@link RequestContext} 的
     * ThreadLocal 此时是默认值（default/anonymous）——用 {@link #auditKey(Object)} 拼 key
     * 永远查不到带租户写入的记录。查询侧必须把写入时的租户/用户显式传进来。
     */
    public List<AuditEntry> getAudit(String tenantId, String userId, Object sessionId) {
        String key = tenantId + ":" + userId + ":" + sessionId;
        return auditLog.getOrDefault(key, List.of());
    }

    /** 按租户查全部调用（合规/成本分析用） */
    public Map<String, List<AuditEntry>> getAuditByTenant(String tenantId) {
        Map<String, List<AuditEntry>> out = new java.util.HashMap<>();
        auditLog.forEach((k, v) -> {
            if (k.startsWith(tenantId + ":")) out.put(k, v);
        });
        return out;
    }

    /** 按 租户:用户 查（追责用） */
    public Map<String, List<AuditEntry>> getAuditByUser(String tenantId, String userId) {
        String prefix = tenantId + ":" + userId + ":";
        Map<String, List<AuditEntry>> out = new java.util.HashMap<>();
        auditLog.forEach((k, v) -> {
            if (k.startsWith(prefix)) out.put(k, v);
        });
        return out;
    }

    public Map<String, List<AuditEntry>> getAllAudit() {
        return auditLog;
    }
}

