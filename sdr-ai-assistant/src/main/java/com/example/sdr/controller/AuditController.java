package com.example.sdr.controller;

import com.example.sdr.entity.AuditLog;
import com.example.sdr.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.0 端到端验证：Audit 查询 Controller。
 *
 * <p>暴露 {@code audit_log} 表，让 {@code scripts/run-e2e.sh} 能验证
 * AuditService 是否在真实运行中记录了 FSM 状态转换。
 *
 * <h2>端点</h2>
 * <ul>
 *   <li>{@code GET /api/audit} — 所有审计记录（最近 100 条）</li>
 *   <li>{@code GET /api/audit/lead/{leadId}} — 单个 lead 的审计链</li>
 *   <li>{@code GET /api/audit/summary} — 聚合：各动作类型 + 各资源类型 计数</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v1.0
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping
    public List<AuditLog> all() {
        return auditLogMapper.selectList(null);
    }

    @GetMapping("/lead/{leadId}")
    public List<AuditLog> byLead(@PathVariable Long leadId) {
        return auditLogMapper.selectList(
                new QueryWrapper<AuditLog>().eq("lead_id", leadId).orderByAsc("created_at"));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<AuditLog> all = auditLogMapper.selectList(null);
        Map<String, Long> byAction = new HashMap<>();
        Map<String, Long> byResource = new HashMap<>();
        for (AuditLog log : all) {
            byAction.merge(log.getAction() == null ? "unknown" : log.getAction(), 1L, Long::sum);
            byResource.merge(log.getResourceType() == null ? "unknown" : log.getResourceType(), 1L, Long::sum);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", all.size());
        result.put("byAction", byAction);
        result.put("byResource", byResource);
        return result;
    }
}