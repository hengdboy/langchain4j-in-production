package com.example.hiring.controller;

import com.example.hiring.api.CorrectionRequest;
import com.example.hiring.api.CreateCandidateRequest;
import com.example.hiring.api.InterviewFeedbackRequest;
import com.example.hiring.api.ReplyRequest;
import com.example.hiring.api.ReviewRequest;
import com.example.hiring.api.SendEventRequest;
import com.example.hiring.core.CandidateContext;
import com.example.hiring.core.CandidateStatus;
import com.example.hiring.core.FsmEvent;
import com.example.hiring.evolution.DpoSampleWriter;
import com.example.hiring.mapper.CandidateEventMapper;
import com.example.hiring.mapper.CandidateMapper;
import com.example.hiring.core.CandidateEvent;
import com.example.hiring.entity.Interview;
import com.example.hiring.mapper.InterviewMapper;
import com.example.hiring.service.CandidatePipelineService;
import com.example.hiring.service.CandidateService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API 层：候选人全生命周期入口。
 *
 * <pre>
 * POST /api/candidates                       投递（触发解析→脱敏→评估→FSM）
 * GET  /api/candidates/{id}                  查候选人（状态/匹配分/决策画像）
 * GET  /api/candidates/{id}/events           状态变更流水
 * GET  /api/candidates/{id}/interview        面试安排与出题
 * POST /api/candidates/{id}/reply            候选人回复（约面/offer 两阶段同入口）
 * POST /api/candidates/{id}/interview/complete  面试官提交反馈
 * POST /api/candidates/{id}/review           HR 复核 ON_HOLD
 * POST /api/candidates/{id}/correction       HR 修正 AI 产出（DPO 样本）
 * POST /api/candidates/{id}/events           调试直发 FSM 事件（教学用）
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidatePipelineService pipeline;
    private final CandidateService candidateService;
    private final CandidateMapper candidateMapper;
    private final CandidateEventMapper candidateEventMapper;
    private final InterviewMapper interviewMapper;
    private final DpoSampleWriter dpoSampleWriter;
    private final ObjectMapper objectMapper;

    @PostMapping
    public CandidateContext submit(@Valid @RequestBody CreateCandidateRequest req) {
        return pipeline.submit(req);
    }

    @GetMapping("/{id}")
    public CandidateContext get(@PathVariable Long id) {
        return candidateMapper.selectById(id);
    }

    @GetMapping("/{id}/events")
    public List<CandidateEvent> events(@PathVariable Long id) {
        return candidateEventMapper.selectList(new QueryWrapper<CandidateEvent>()
                .eq("candidate_id", id).orderByAsc("id"));
    }

    @GetMapping("/{id}/interview")
    public Interview interview(@PathVariable Long id) {
        return interviewMapper.selectList(new QueryWrapper<Interview>()
                .eq("candidate_id", id).orderByDesc("id").last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    @PostMapping("/{id}/reply")
    public CandidateContext reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest req) {
        return pipeline.reply(id, req.text());
    }

    @PostMapping("/{id}/interview/complete")
    public CandidateContext complete(@PathVariable Long id,
                                     @Valid @RequestBody InterviewFeedbackRequest req) {
        return pipeline.completeInterview(id, req.feedback());
    }

    @PostMapping("/{id}/review")
    public CandidateContext review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return pipeline.review(id, req.approve(), req.reason());
    }

    @PostMapping("/{id}/correction")
    public Map<String, Object> correction(@PathVariable Long id,
                                          @Valid @RequestBody CorrectionRequest req) {
        var sample = dpoSampleWriter.write(
                req.prompt(), req.chosen(), req.rejected(),
                DpoSampleWriter.SOURCE_FEEDBACK, "{\"candidateId\":" + id + "}");
        return Map.of("dpoSampleId", sample.getId(), "source", sample.getSource());
    }

    /** 调试直发 FSM 事件（教学/排障用；Guard 拒绝时返回 accepted=false） */
    @PostMapping("/{id}/events")
    public Map<String, Object> sendEvent(@PathVariable Long id,
                                         @Valid @RequestBody SendEventRequest req) {
        CandidateContext before = candidateMapper.selectById(id);
        if (before == null) {
            throw new IllegalArgumentException("Candidate not found: " + id);
        }
        CandidateStatus from = before.getStatus();
        FsmEvent event = FsmEvent.valueOf(req.event());
        Map<String, Object> overrides = buildOverrides(req);
        CandidateContext after = candidateService.processEvent(id, event, overrides);
        return Map.of(
                "accepted", after.getStatus() != from,
                "fromStatus", from.name(),
                "toStatus", after.getStatus().name(),
                "candidateId", id);
    }

    private Map<String, Object> buildOverrides(SendEventRequest req) {
        var m = new java.util.HashMap<String, Object>();
        if (req.verdict() != null) m.put("verdict", req.verdict());
        if (req.replyDecision() != null) m.put("replyDecision", req.replyDecision());
        if (req.evalOutcome() != null) m.put("evalOutcome", req.evalOutcome());
        if (req.offerDecision() != null) m.put("offerDecision", req.offerDecision());
        if (req.humanApproved() != null) m.put("humanApproved", req.humanApproved());
        return m;
    }
}
