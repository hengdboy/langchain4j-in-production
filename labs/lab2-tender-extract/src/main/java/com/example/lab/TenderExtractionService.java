package com.example.lab;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Lab2 抽取服务：声明式接口（AiServices），返回类型就是结构化契约。
 *
 * <p>方法签名返回 {@link RawTender}——langchain4j 会按字段生成 JSON Schema 下发，
 * 并把模型输出反序列化成对象。字段全部 String 的原因见 {@link RawTender}。
 * @author hengdboy
 */
public final class TenderExtractionService {

    private final Assistant assistant;

    public TenderExtractionService(ChatModel model) {
        this.assistant = dev.langchain4j.service.AiServices.create(Assistant.class, model);
    }

    public TenderNormalizer.TenderRecord extract(String announcementText) {
        return TenderNormalizer.normalize(assistant.extract(announcementText));
    }

    interface Assistant {
        @UserMessage("""
                你是招投标助理。从下面的公告原文里抽取字段，严格按 JSON 输出：
                projectName（项目名，去掉"招标公告/采购公告"等后缀）、
                purchaserName（招标人/采购人）、
                budgetYuan（预算金额，保留原文写法原样抄出，如"3,500万元"）、
                submissionDeadline（投标/报价截止时间，保留原文写法）、
                requiredQualifications（资质要求列表，原文逐条抄，没有就空数组）、
                performanceBondYuan（投标/履约保证金，保留原文写法；明确写"不收取"输出"不收取"；按比例的输出原文如"合同金额的 5%"）。

                公告原文：
                {{announcement}}
                """)
        RawTender extract(@V("announcement") String announcement);
    }
}
