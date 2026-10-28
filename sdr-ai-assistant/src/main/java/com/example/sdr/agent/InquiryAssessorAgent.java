package com.example.sdr.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 阶段一 Agent：询盘质量评估。
 *
 * <h2>与 {@link LeadSearchAgent} 的关系</h2>
 * <p>{@code AgenticServices.agentBuilder(Class)} 要求每个 Agent 接口<b>恰好一个</b>
 * {@code @Agent} 方法，故拆为两个单方法接口：Dorks 生成（LeadSearchAgent）与本询盘评估。
 *
 * <h2>职责</h2>
 * <p>解析一封询盘邮件，输出结构化"AI 评估简报"（Ch07 结构化输出 record）。
 * demo 阶段一真实调用本 Agent：intentScore 高 + 有决策人 → QUALIFY_OK，否则 QUALIFY_FAIL。
 *
 * @author langchain4j-tutorial
 * @since v0.24
 */
public interface InquiryAssessorAgent {

    /**
     * 解析一封询盘邮件，输出结构化的"AI 评估简报"。
     */
    @SystemMessage("""
            你是询盘质量评估助手。判断询盘邮件的真实采购意向（0-1 分），
            并提取客户公司、采购品类、决策人等关键信息。
            """)
    @UserMessage("""
            请评估以下询盘：
            {{emailBody}}
            """)
    @Agent(value = "评估询盘邮件质量，输出结构化评估简报（意向分/公司/品类/决策人）",
           outputKey = "assessment")
    InquiryAssessment assessInquiry(@V("emailBody") String emailBody);

    /**
     * 询盘评估结果（Ch07 结构化输出 record）。
     */
    record InquiryAssessment(
            @Description("采购意向分，0-1 范围，越高越真") Double intentScore,
            @Description("客户公司名") String companyName,
            @Description("采购品类关键词") String productCategory,
            @Description("是否提到决策人/采购联系人") boolean hasDecisionMaker,
            @Description("评估理由一句话") String reasoning
    ) {}
}
