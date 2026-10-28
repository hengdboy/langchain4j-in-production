package com.example.hiring.controller;

import com.example.hiring.entity.AgentCall;
import com.example.hiring.entity.BanditState;
import com.example.hiring.mapper.AgentCallMapper;
import com.example.hiring.mapper.BanditStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.0 端到端验证：Observability 查询 Controller。
 *
 * <p>暴露 {@code agent_call} 表，让 {@code scripts/run-e2e.sh} 能验证
 * CostChatModelListener 是否在真实运行中写入了调用记录。
 *
 * <h2>端点</h2>
 * <ul>
 * <li>{@code GET /api/observability/agent-calls} — 所有调用（最近 100 条）</li>
 * <li>{@code GET /api/observability/agent-calls/lead/{candidateId}} — 单个 lead 的调用链</li>
 * <li>{@code GET /api/observability/summary} — 聚合：总调用数 / 总 token / 各 agent 调用数</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v1.0
 */
@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final AgentCallMapper agentCallMapper;
    private final BanditStateMapper banditStateMapper;

    @GetMapping("/agent-calls")
    public List<AgentCall> allAgentCalls() {
        // 教学版：取全部（生产应分页）
        return agentCallMapper.selectList(null);
    }

    @GetMapping("/agent-calls/lead/{candidateId}")
    public List<AgentCall> callsByLead(@PathVariable Long candidateId) {
        return agentCallMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentCall>()
                        .eq("candidate_id", candidateId)
                        .orderByAsc("created_at"));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<AgentCall> all = agentCallMapper.selectList(null);
        long total = all.size();
        long totalInput = all.stream().mapToLong(c -> c.getInputTokens() == null ? 0 : c.getInputTokens()).sum();
        long totalOutput = all.stream().mapToLong(c -> c.getOutputTokens() == null ? 0 : c.getOutputTokens()).sum();
        long failed = all.stream().filter(c -> "failed".equals(c.getStatus())).count();
        Map<String, Long> byAgent = new HashMap<>();
        for (AgentCall c : all) {
            byAgent.merge(c.getAgentName() == null ? "unknown" : c.getAgentName(), 1L, Long::sum);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("totalCalls", total);
        result.put("totalInputTokens", totalInput);
        result.put("totalOutputTokens", totalOutput);
        result.put("failedCalls", failed);
        result.put("byAgent", byAgent);
        return result;
    }

    @GetMapping("/bandit/state")
    public List<BanditState> banditState() {
        return banditStateMapper.selectList(null);
    }
}