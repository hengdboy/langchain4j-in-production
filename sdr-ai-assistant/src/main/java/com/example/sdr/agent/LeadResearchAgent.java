package com.example.sdr.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 阶段二 Agent：深度背调与 KP 验真。
 *
 * <h2>职责</h2>
 * <p>给定一条新线索（公司 + 国家 + 邮箱），调用 3 个背调工具综合判定：
 * <ol>
 *   <li>{@link com.example.sdr.tools.CustomsLookupTool#lookup} —— 查海关进出口记录</li>
 *   <li>{@link com.example.sdr.tools.SmtpVerifyTool#verify} —— SMTP 验真邮箱</li>
 *   <li>{@link com.example.sdr.tools.KpFinderTool#find} —— 找决策人 KP</li>
 * </ol>
 * 输出结构化 {@link ResearchReport}，由 {@link com.example.sdr.service.LeadPipelineService}
 * 按规则分支：smtpVerified && kpFound → QUALIFY_OK，否则 QUALIFY_FAIL。
 *
 * <h2>单方法接口约束</h2>
 * <p>{@code AgenticServices.agentBuilder(Class)} 校验接口内<b>恰好一个</b>
 * {@code @Agent} 方法（与 {@link InquiryAssessorAgent} 一致）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
public interface LeadResearchAgent {

    /**
     * 深度背调：基于公司名 + 国家 + 邮箱，输出结构化 ResearchReport。
     */
    @SystemMessage("""
            你是 B2B 外贸背调专家。给定一条新线索（公司、国家、客户邮箱），
            请按以下顺序调用工具完成背调：
            1) 先调 smtpVerify 验真客户邮箱（不要漏，邮箱无效直接判 failed）
            2) 调 customsLookup 查公司海关进出口记录（有记录=强信号）
            3) 调 kpFinder 找决策人 KP（找到 KP=可触达性高）
            综合三者，给出 pass/fail 判定与置信度。
            """)
    @UserMessage("""
            公司：{{company}}
            国家：{{country}}
            客户邮箱：{{email}}
            请完成背调并输出 ResearchReport。
            """)
    @Agent(value = "深度背调：海关/SMTP/KP 综合判定",
           outputKey = "research")
    ResearchReport research(@V("company") String company,
                            @V("country") String country,
                            @V("email") String email);

    /**
     * 背调报告。
     *
     * @param passed        是否通过背调（passed=true → QUALIFY_OK；false → QUALIFY_FAIL）
     * @param confidence    置信度 0-1
     * @param smtpVerified  SMTP 验真结果（true/false；unknown 也映射为 false）
     * @param kpFound       是否找到决策人 KP
     * @param kpName        KP 姓名（可空）
     * @param kpEmail       KP 邮箱（可空）
     * @param importRecords 海关命中品类列表（可空）
     * @param reasoning     一句话背调理由
     */
    record ResearchReport(
            @Description("是否通过背调：true→QUALIFY_OK；false→QUALIFY_FAIL") boolean passed,
            @Description("置信度 0-1，越高越可靠") double confidence,
            @Description("SMTP 验真结果") boolean smtpVerified,
            @Description("是否找到决策人 KP") boolean kpFound,
            @Description("KP 姓名") String kpName,
            @Description("KP 邮箱") String kpEmail,
            @Description("海关命中品类列表") java.util.List<String> importRecords,
            @Description("一句话背调理由") String reasoning
    ) {}
}