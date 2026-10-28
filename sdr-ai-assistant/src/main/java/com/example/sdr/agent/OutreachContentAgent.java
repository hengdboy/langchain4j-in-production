package com.example.sdr.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Ch24 阶段三 Agent：DNA 匹配 + 多语言开发信生成。
 *
 * <h2>职责</h2>
 * <p>根据背调结果（公司、国家、KP、进口品类）生成定制化开发信：
 * <ul>
 *   <li>按目标语种（国家 → 语种映射由调用方给出）写正文</li>
 *   <li>邮件必须提到客户的海关进口品类（证明做过功课）</li>
 *   <li>包含 KP 称呼、CTA、退订合规</li>
 * </ul>
 * 输出结构化 {@link OutreachEmail}，由 {@link com.example.sdr.service.LeadPipelineService}
 * 落库 + 交 MailGateway 发送。
 *
 * <p>RAG 检索（DNA 知识库匹配）在 v0.29 引入，当前用 mock 的 importRecords 代替。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
public interface OutreachContentAgent {

    /**
     * 生成开发信（多语言）。
     */
    @SystemMessage("""
            你是 B2B 外贸开发信撰写专家。基于背调信息生成一封高转化率的定制开发信。
            要求：
            1. 用指定语种撰写（{{language}}），非英语时主题与正文都要翻译
            2. 正文开头必须提到客户的海关进口品类（证明做过功课）
            3. 用 {{kpName}} 的称呼开头（若有）
            4. 结尾有明确 CTA（约 15 分钟电话/索取报价单）
            5. 附退订说明（合规）
            6. 正文 150-250 词，语气专业不浮夸
            """)
    @UserMessage("""
            公司：{{company}}
            国家：{{country}}
            语种：{{language}}
            KP 称呼：{{kpName}}
            进口品类：{{importRecords}}
            产品卖点：{{productKeywords}}
            请生成开发信。
            """)
    @Agent(value = "按背调信息生成多语言定制开发信",
           outputKey = "email")
    OutreachEmail generate(@V("company") String company,
                           @V("country") String country,
                           @V("language") String language,
                           @V("kpName") String kpName,
                           @V("importRecords") String importRecords,
                           @V("productKeywords") String productKeywords);

    /**
     * 开发信内容。
     *
     * @param subject  邮件主题
     * @param body     邮件正文（Markdown）
     * @param language 实际使用语种
     */
    record OutreachEmail(
            @Description("邮件主题（已按目标语种翻译）") String subject,
            @Description("邮件正文（Markdown，目标语种）") String body,
            @Description("实际使用语种代码（如 en/de/zh）") String language
    ) {}
}