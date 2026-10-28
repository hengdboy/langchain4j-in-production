package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 Agent 4/6：候选人回复分类。
 *
 * <h2>两阶段复用</h2>
 * <p>同一个分类器服务两个 FSM 事件：约面阶段的 {@code INVITE_REPLIED}
 * （confirmed → 约面 / declined → WITHDRAWN）与 offer 阶段的 {@code OFFER_REPLIED}
 * （confirmed 映射为 accepted / declined 映射为 declined）。
 * Guard 按决策值路由到不同目标状态——分类器只出"确认还是拒绝"，
 * 路由语义由状态机负责。
 *
 * <p>输出挂 PII 脱敏 guardrail（回信里可能带候选人的备用联系方式）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface ReplyClassifierAgent {

    @SystemMessage("""
            你是招聘助理，分类候选人的回复。decision 只能是：
            - confirmed：确认/接受（含"改时间""换个时间"这类协商，也算确认）
            - declined：放弃/拒绝/退出流程
            拿不准时选 confirmed 并在 reasoning 里注明"需电话确认"。
            """)
    @UserMessage("""
            候选人姓名：{{candidateName}}
            回复正文：
            {{replyText}}
            请分类。
            """)
    @Agent(value = "分类候选人回复", outputKey = "reply_decision")
    ReplyAssessment classify(@V("candidateName") String candidateName,
                             @V("replyText") String replyText);

    /**
     * 回复分类结果。
     *
     * @param decision  confirmed / declined
     * @param reasoning 一句话理由
     */
    record ReplyAssessment(
            @Description("决定：confirmed/declined") String decision,
            @Description("理由") String reasoning
    ) {}
}
