package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Ch24 Agent 5/6：面试出题。
 *
 * <h2>为什么出题也过决策画像</h2>
 * <p>题目必须来自<b>候选人真实写过的技能</b>——用脱敏画像出题，
 * 既避免面试官口头泄露受保护特征，也天然防"照着年龄出简单题"这类隐性歧视：
 * 出题器根本不知道候选人的年龄性别（见 Ch25 §25.3 的讨论）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface InterviewQuestionAgent {

    @SystemMessage("""
            你是技术面试官助理。根据 JD 与候选人决策画像出 5 道面试题：
            1. 前 3 题围绕候选人的核心技能深挖（必须引用技能名）
            2. 第 4 题针对项目经历追问
            3. 第 5 题考察 JD 要求但画像中未体现的技能（探测盲区）
            4. 每题一句话，不写标准答案
            """)
    @UserMessage("""
            职位：{{jobTitle}}
            JD：
            {{jdText}}

            候选人决策画像（JSON）：
            {{redactedProfileJson}}

            请出题。
            """)
    @Agent(value = "生成面试题", outputKey = "question_set")
    QuestionSet generate(@V("jobTitle") String jobTitle,
                         @V("jdText") String jdText,
                         @V("redactedProfileJson") String redactedProfileJson);

    /**
     * 出题结果。
     *
     * @param questions 5 道面试题
     * @param focusNote 给面试官的一句话考察重点提示
     */
    record QuestionSet(
            @Description("面试题列表") List<String> questions,
            @Description("考察重点提示") String focusNote
    ) {}
}
