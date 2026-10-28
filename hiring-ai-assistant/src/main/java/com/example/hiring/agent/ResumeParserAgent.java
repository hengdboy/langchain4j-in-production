package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Ch24 Agent 1/6：简历解析。
 *
 * <h2>职责</h2>
 * <p>把非结构化简历原文转成结构化 {@link ParsedProfile}。原则：<b>只提取，不评估</b>——
 * 好坏判断是 {@link MatchEvaluatorAgent} 的事，解析器多说一个字都是引入偏见的机会。
 *
 * <p>产出的画像仍含姓名/邮箱/电话（后续约面要用），但进入评估前会被
 * {@code DecisionFieldFilter} 过一遍决策白名单（见 Ch25 §25.3）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface ResumeParserAgent {

    @SystemMessage("""
            你是招聘简历解析器。从简历原文提取结构化信息，规则：
            1. 只提取原文明确写出的内容，提取不到的字段填 null，禁止推测或编造
            2. yearsOfExperience 是整数工作年限（原文"3年经验"→3）
            3. skills 保留原文写法，不要同义词归并
            4. projects 每条一句话概括（项目名+一句话职责）
            """)
    @UserMessage("""
            简历原文：
            {{resumeText}}
            请解析。
            """)
    @Agent(value = "解析简历为结构化画像", outputKey = "parsed_profile")
    ParsedProfile parse(@V("resumeText") String resumeText);

    /**
     * 解析结果。
     *
     * @param name               姓名
     * @param email              邮箱
     * @param phone              电话
     * @param yearsOfExperience  工作年限（年）
     * @param skills             技能列表（保留原文写法）
     * @param education          最高学历描述
     * @param projects           项目经历（一句话/条）
     * @param notes              其他值得注意的信息
     */
    record ParsedProfile(
            @Description("姓名") String name,
            @Description("邮箱") String email,
            @Description("电话") String phone,
            @Description("工作年限（整数年）") Integer yearsOfExperience,
            @Description("技能列表") List<String> skills,
            @Description("最高学历") String education,
            @Description("项目经历") List<String> projects,
            @Description("备注") String notes
    ) {}
}
