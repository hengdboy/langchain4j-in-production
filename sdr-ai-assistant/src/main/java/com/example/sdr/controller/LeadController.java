package com.example.sdr.controller;

import com.example.sdr.api.CreateLeadRequest;
import com.example.sdr.api.EventResult;
import com.example.sdr.api.ReplyRequest;
import com.example.sdr.api.SendEventRequest;
import com.example.sdr.core.FsmEvent;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadStatus;
import com.example.sdr.mapper.LeadMapper;
import com.example.sdr.service.LeadPipelineService;
import com.example.sdr.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ch24 API 层：销售线索接口。
 *
 * <h2>用法</h2>
 * <pre>
 * # 创建线索（v0.26 起事件驱动：自动背调→内容→发信→STEP1_SENT 或 POOL）
 * curl -X POST http://localhost:8091/api/leads \
 *   -H "Content-Type: application/json" \
 *   -d '{"email":"buyer@example.com","company":"ACME Corp","country":"US"}'
 *
 * # 模拟客户回信（阶段五：自动判级 → INTENT_A/B/C/D）
 * curl -X POST http://localhost:8091/api/leads/1/reply \
 *   -H "Content-Type: application/json" \
 *   -d '{"text":"Please send a quote for 500 IoT gateways and call me."}'
 *
 * # 手动发送 FSM 事件（低层控制）
 * curl -X POST http://localhost:8091/api/leads/1/events \
 *   -H "Content-Type: application/json" \
 *   -d '{"event":"SALES_HANDOVER","salesAccepted":true}'
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadMapper leadMapper;
    private final LeadService leadService;
    private final LeadPipelineService pipeline;

    @PostMapping
    public LeadContext create(@Valid @RequestBody CreateLeadRequest req) {
        return pipeline.ingest(req);
    }

    @PostMapping("/{id}/reply")
    public EventResult reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        LeadContext before = leadMapper.selectById(id);
        if (before == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found: " + id);
        }
        LeadContext after = pipeline.onReply(id, req.text());
        return new EventResult(before.getStatus() != after.getStatus(),
                before.getStatus(), after.getStatus(), after);
    }

    @GetMapping
    public List<LeadContext> list(@RequestParam(required = false) LeadStatus status) {
        if (status == null) {
            return leadMapper.selectList(null);
        }
        return leadMapper.findByStatus(status);
    }

    @GetMapping("/{id}")
    public LeadContext get(@PathVariable Long id) {
        LeadContext lead = leadMapper.selectById(id);
        if (lead == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found: " + id);
        }
        return lead;
    }

    @PostMapping("/{id}/events")
    public EventResult sendEvent(@PathVariable Long id, @RequestBody SendEventRequest req) {
        LeadContext before = leadMapper.selectById(id);
        if (before == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found: " + id);
        }

        FsmEvent event;
        try {
            event = FsmEvent.valueOf(req.event());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid event: " + req.event() + ", expected one of " + List.of(FsmEvent.values()));
        }

        Map<String, Object> overrides = new HashMap<>();
        if (req.intentGrade() != null) overrides.put("intentGrade", req.intentGrade());
        if (req.smtpVerified() != null) overrides.put("smtpVerified", req.smtpVerified());
        if (req.salesAccepted() != null) overrides.put("salesAccepted", req.salesAccepted());

        LeadStatus from = before.getStatus();
        LeadContext after = leadService.processEvent(id, event, overrides);
        LeadStatus to = after.getStatus();

        // 自循环（如 TIMEOUT_72H）不改变状态但也是 accepted；API 主信号按状态迁移判定
        return new EventResult(from != to, from, to, after);
    }
}
