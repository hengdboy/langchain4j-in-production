package com.example.sdr.audit;

import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.core.LeadTransitionedEvent;
import com.example.sdr.entity.AuditLog;
import com.example.sdr.mapper.AuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.29 AuditService 集成测试（audit_log 表落库 + 事件监听）。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Slf4j
@SpringBootTest
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Test
    @DisplayName("【手动 record】写入 audit_log")
    void testManualRecord() {
        long before = auditLogMapper.selectCount(null);
        auditService.record("user:alice", "export", "lead", 42L, 42L,
                "{\"basis\":\"contract\",\"reason\":\"audit prep\"}");
        long after = auditLogMapper.selectCount(null);
        assertThat(after).isEqualTo(before + 1);

        List<AuditLog> logs = auditLogMapper.selectList(null);
        AuditLog latest = logs.get(logs.size() - 1);
        assertThat(latest.getActor()).isEqualTo("user:alice");
        assertThat(latest.getAction()).isEqualTo("export");
        assertThat(latest.getLeadId()).isEqualTo(42L);
        assertThat(latest.getDetail()).contains("contract");
        log.info("✅ 手动 record 写入成功：id={} actor={} action={}", latest.getId(), latest.getActor(), latest.getAction());
    }

    @Test
    @DisplayName("【事件驱动】LeadTransitionedEvent 触发审计")
    void testEventListener() {
        long before = auditLogMapper.selectCount(null);
        publisher.publishEvent(new LeadTransitionedEvent(
                7L, LeadStatus.RAW, LeadStatus.QUALIFIED, FsmEvent.QUALIFY_OK));
        // @EventListener 同步触发，无需 sleep
        long after = auditLogMapper.selectCount(null);
        assertThat(after).isEqualTo(before + 1);

        List<AuditLog> logs = auditLogMapper.selectList(null);
        AuditLog latest = logs.get(logs.size() - 1);
        assertThat(latest.getLeadId()).isEqualTo(7L);
        assertThat(latest.getAction()).isEqualTo("update");
        assertThat(latest.getResourceType()).isEqualTo("lead");
        assertThat(latest.getDetail()).contains("RAW").contains("QUALIFIED");
        log.info("✅ 事件驱动 audit 写入：id={} leadId={} detail={}",
                latest.getId(), latest.getLeadId(), latest.getDetail());
    }
}