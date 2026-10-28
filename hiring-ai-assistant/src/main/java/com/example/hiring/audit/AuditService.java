package com.example.hiring.audit;

import com.example.hiring.core.CandidateTransitionedEvent;
import com.example.hiring.entity.AuditLog;
import com.example.hiring.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * v0.29 Ch16 Audit：GDPR 合规审计 service。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>监听 {@link CandidateTransitionedEvent}：把每次 FSM 转换写入 {@code audit_log}</li>
 *   <li>提供 {@link #record} 方法供业务侧手动触发（如 lead 创建 / 数据导出）</li>
 * </ul>
 *
 * <h2>GDPR Article 30</h2>
 * <p>必须记录：谁（actor）、做了什么（action）、涉及哪个资源（resourceType+resourceId）、
 * 何时（created_at）、为何（detail JSON，可选 GDPR legal basis）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /** FSM 状态转换：作为合规审计核心入口（v0.29 教学版） */
    @EventListener
    public void onCandidateTransitioned(CandidateTransitionedEvent event) {
        record(
                "system",
                "update",
                "candidate",
                event.candidateId(),
                event.candidateId(),
                String.format("{\"from\":\"%s\",\"to\":\"%s\",\"event\":\"%s\"}",
                        event.from(), event.to(), event.event())
        );
    }

    /**
     * 写入一条审计记录。
     *
     * @param actor         谁（system / user_id / agent:xxx）
     * @param action        动作（create / read / update / delete / export）
     * @param resourceType  资源类型（lead / email / dpo_sample）
     * @param resourceId    资源 ID
     * @param candidateId        关联线索（可选）
     * @param detail        详情（JSON 字符串）
     */
    public void record(String actor, String action, String resourceType,
                       Long resourceId, Long candidateId, String detail) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setCandidateId(candidateId);
        log.setDetail(detail);
        log.setCreatedAt(Instant.now());
        try {
            auditLogMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不阻塞主流程，但必须记录到日志（合规要求）
            AuditService.log.error("⚠️ [Audit] persist audit_log failed: {}", e.getMessage());
        }
    }
}