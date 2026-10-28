package com.example.hiring.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 Agent 3/6：约面邀请生成（Bandit 实验的载体）。
 *
 * <h2>tone × cta 臂空间</h2>
 * <p>语气（tone ∈ formal/warm/concise）× 行动号召（cta ∈ calendar-link/reply-slot）
 * 组成 6 个臂，由 {@code BanditOptimizer} 按历史回复率做 Thompson Sampling 选择。
 * 哪种话术让更多候选人回复——不靠拍脑袋，靠数据（见 Ch25 §25.4）。
 *
 * <p>输出挂 PII 脱敏 guardrail：LLM 有小概率把画像里的联系方式抄进正文。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public interface InviteContentAgent {

    @SystemMessage("""
            你是招聘官助理，生成约面邀请邮件（中文）。要求：
            1. 主题一行，正文 80-150 字，署名"星航科技 招聘团队"
            2. 语气严格执行 tone 参数：formal=正式商务 / warm=亲切热情 / concise=简洁直给
            3. 行动号召严格执行 cta 参数：calendar-link=引导点击选座链接自选时间 / reply-slot=请回复两个可选时间段
            4. 只写约面相关内容，不评价候选人简历
            """)
    @UserMessage("""
            候选人姓名：{{candidateName}}
            应聘职位：{{jobTitle}}
            tone：{{tone}}
            cta：{{cta}}
            请生成邀请邮件。
            """)
    @Agent(value = "生成约面邀请邮件", outputKey = "invite_email")
    InviteEmail generate(@V("candidateName") String candidateName,
                         @V("jobTitle") String jobTitle,
                         @V("tone") String tone,
                         @V("cta") String cta);

    /**
     * 邀请邮件。
     *
     * @param subject  主题
     * @param body     正文
     * @param language 语种（教学版恒为 zh）
     */
    record InviteEmail(
            @Description("邮件主题") String subject,
            @Description("邮件正文") String body,
            @Description("语种") String language
    ) {}
}
