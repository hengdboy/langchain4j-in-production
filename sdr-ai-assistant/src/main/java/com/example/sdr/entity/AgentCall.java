package com.example.sdr.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v0.29 Ch22 Observability：{@code agent_call} 表实体。
 *
 * <p>由 {@code CostChatModelListener} 在 ChatModel 每次请求/响应/出错时写入。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Data
@NoArgsConstructor
@TableName("agent_call")
public class AgentCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leadId;
    private String agentName;

    /** 调用的工具列表（JSON 数组字符串） */
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