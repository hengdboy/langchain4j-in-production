package com.example.hiring.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 调用埋点（agent_call 表，接  CostChatModelListener）。
 *
 * @author hengdboy
 * @since v0.30
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_call")
public class AgentCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    /** Agent 名（如 MatchEvaluatorAgent） */
    private String agentName;

    /** 调用的工具列表（JSON） */
    private String toolsUsed;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer totalTokens;

    private Integer durationMs;

    /** success / failed / timeout */
    private String status;

    private String errorMessage;

    private Instant createdAt;
}
