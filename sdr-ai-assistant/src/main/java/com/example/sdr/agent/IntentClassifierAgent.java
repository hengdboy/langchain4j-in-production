package com.example.sdr.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 阶段五 Agent：回信监听 + 意向分级。
 *
 * <h2>职责</h2>
 * <p>给定客户对开发信的回信正文，判定意向等级 A/B/C/D：
 * <ul>
 *   <li><b>A 高意向</b>——明确询价/要报价单/约电话，可直接转销售</li>
 *   <li><b>B 中意向</b>——感兴趣但还在了解/比较</li>
 *   <li><b>C 低意向</b>——泛泛回应/要资料/暂不需要</li>
 *   <li><b>D 无意向</b>——退订/拒绝/无关回复</li>
 * </ul>
 * 输出结构化 {@link IntentAssessment}，由 {@link com.example.sdr.service.LeadPipelineService}
 * 映射为 FSM 事件 {@code EMAIL_REPLIED(intentGrade=A/B/C/D)}。
 *
 * <h2>与 {@link InquiryAssessorAgent} 的区别</h2>
 * <p>InquiryAssessor 评估"主动询盘质量"（阶段一）；本 Agent 分级"对开发信的回信"（阶段五）。
 * 两者输入、输出、语义均不同。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
public interface IntentClassifierAgent {

    /**
     * 判定回信意向等级。
     */
    @SystemMessage("""
            你是 B2B 销售意向分级专家。分析客户对开发信的回信，判定意向等级。
            等级定义：
            - A 高意向：明确询价、索要报价单、约时间电话、有具体采购量/预算
            - B 中意向：表达兴趣但仍在了解/比较阶段，需要进一步跟进
            - C 低意向：泛泛回应、只要产品资料、称暂不需要
            - D 无意向：退订、拒绝、纯客套或无关内容
            输出等级 + 理由。
            """)
    @UserMessage("""
            客户回信正文：
            {{replyText}}

            背景（供参考）：
            公司：{{company}}
            我们推销的产品：{{productKeywords}}
            请判定意向等级。
            """)
    @Agent(value = "判定客户回信意向等级 A/B/C/D",
           outputKey = "intent")
    IntentAssessment classify(@V("replyText") String replyText,
                              @V("company") String company,
                              @V("productKeywords") String productKeywords);

    /**
     * 意向分级结果。
     *
     * @param grade    意向等级 A/B/C/D
     * @param reasoning 判定理由
     * @param suggestCall 是否建议销售立即回电（A/B 级为 true）
     */
    record IntentAssessment(
            @Description("意向等级：A/B/C/D") String grade,
            @Description("判定理由一句话") String reasoning,
            @Description("是否建议销售立即跟进") boolean suggestCall
    ) {}
}