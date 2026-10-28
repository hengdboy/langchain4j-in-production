package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Agent 6/6：面试评价汇总。
 *
 * <h2>输入是面试官的原始反馈，输出是结构化评价</h2>
 * <p>overall（PASS/FAIL）驱动 {@code EVAL_PASS / EVAL_FAIL} 两个 FSM 事件；
 * strengths/concerns 进评价存档；汇总文本挂 PII 脱敏 + 歧义特征双输出护栏
 * ——面试官手打的反馈里什么都有，落库前必须过筛。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface EvaluationAgent {

    @SystemMessage("""
            你是面试评价汇总专家。把面试官的原始反馈整理成结构化评价：
            1. overall 只能是 PASS（推荐进 offer）/ FAIL（不通过）
            2. rating 为 1-5 整数
            3. strengths/concerns 每条必须引用反馈中的具体内容
            4. 汇总只谈专业能力，禁止出现年龄、性别、婚育、籍贯等与岗位无关的特征
            """)
    @UserMessage("""
            应聘职位：{{jobTitle}}

            候选人决策画像（JSON，供对照）：
            {{redactedProfileJson}}

            面试官原始反馈：
            {{interviewerFeedback}}

            请汇总。
            """)
    @Agent(value = "汇总面试评价", outputKey = "evaluation_summary")
    EvaluationSummary summarize(@V("jobTitle") String jobTitle,
                                @V("redactedProfileJson") String redactedProfileJson,
                                @V("interviewerFeedback") String interviewerFeedback);

    /**
     * 评价汇总。
     *
     * @param overall PASS / FAIL
     * @param rating  1-5
     * @param strengths 亮点（引用反馈原文）
     * @param concerns   疑虑（引用反馈原文）
     * @param summary    一段话总评
     */
    record EvaluationSummary(
            @Description("结论：PASS/FAIL") String overall,
            @Description("评分 1-5") Integer rating,
            @Description("亮点") List<String> strengths,
            @Description("疑虑") List<String> concerns,
            @Description("总评") String summary
    ) {}
}
