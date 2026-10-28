package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Ch24 Agent 2/6：岗位匹配评估（全套 pipeline 里被护栏看得最紧的一个）。
 *
 * <h2>输入侧</h2>
 * <p>只喂<b>决策画像</b>（{@code DecisionFieldFilter} 白名单过滤后）——
 * 姓名、联系方式、年龄、性别、婚育、籍贯在 prompt 里根本不存在，
 * 而不是"告诉模型别用"（提示词约束是君子协定，字段不存在才是硬约束，见 Ch25 §25.3）。
 *
 * <h2>输出侧</h2>
 * <p>挂 {@code DiscriminationGuardrail}：理由里出现受保护特征词直接拦截。
 * 拦截后 pipeline 不自动重试、不自动决策，停下来等人工（合规违规宁可停，不可错）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface MatchEvaluatorAgent {

    @SystemMessage("""
            你是岗位匹配评估专家。基于给定的候选人决策画像与职位 JD 做评估。
            规则：
            1. 画像中只有技能、年限、学历、项目——这就是你能看到的全部，禁止推测画像之外的任何特征
            2. verdict 只能是 SHORTLIST（入围）/ REVIEW（待定）/ REJECT（不通过）
            3. score 为 0-100 整数
            4. 每条 reason 必须引用画像中的具体证据（技能名/年限/项目），禁止空泛评价
            """)
    @UserMessage("""
            职位：{{jobTitle}}
            JD：
            {{jdText}}

            候选人决策画像（JSON）：
            {{redactedProfileJson}}

            请评估。
            """)
    @Agent(value = "评估候选人与职位的匹配度", outputKey = "match_verdict")
    MatchVerdict evaluate(@V("jobTitle") String jobTitle,
                          @V("jdText") String jdText,
                          @V("redactedProfileJson") String redactedProfileJson);

    /**
     * 评估结论。
     *
     * @param score   匹配分 0-100
     * @param verdict SHORTLIST / REVIEW / REJECT
     * @param reasons 评估理由（每条都要有画像证据；拒信可解释性的数据源）
     */
    record MatchVerdict(
            @Description("匹配分 0-100") Integer score,
            @Description("结论：SHORTLIST/REVIEW/REJECT") String verdict,
            @Description("评估理由列表") List<String> reasons
    ) {}
}
