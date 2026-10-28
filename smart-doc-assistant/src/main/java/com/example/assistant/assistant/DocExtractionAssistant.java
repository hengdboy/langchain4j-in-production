package com.example.assistant.assistant;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

import java.time.LocalDate;
import java.util.List;

/**
 * Ch07 新增：文档/合同关键信息提取助手
 *
 * 演示：
 * - 嵌套 POJO 结构化输出
 * - @Description 字段语义提示
 * - enum 分类
 * - List 嵌套
 * - 找不到字段填 null 而非编造
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "jsonModel")   // ← 指定低温度 JSON 模型，多模型 Bean 需 EXPLICIT
public interface DocExtractionAssistant {

    @SystemMessage(fromResource = "prompts/doc-extraction-system.txt")
    @UserMessage("""
        请从以下文档内容中提取关键信息：

        {{document}}
        """)
    DocExtraction extract(String document);

    // ========== 结构化输出类型 ==========

    /** 文档类型 */
    enum DocType {
        /** 合同 */
        CONTRACT,
        /** 发票 */
        INVOICE,
        /** 简历 */
        RESUME,
        /** 报告 */
        REPORT,
        /** 其他 */
        OTHER
    }

    /** 相关方信息 */
    record Party(
        @dev.langchain4j.model.output.structured.Description("甲方/乙方/公司名称/个人姓名")
        String name,
        @dev.langchain4j.model.output.structured.Description("联系电话，找不到填 null")
        String contact,
        @dev.langchain4j.model.output.structured.Description("地址，找不到填 null")
        String address
    ) {}

    /** 文档关键信息提取结果 */
    record DocExtraction(
        @dev.langchain4j.model.output.structured.Description("文档类型")
        DocType docType,

        @dev.langchain4j.model.output.structured.Description("文档标题")
        String title,

        @dev.langchain4j.model.output.structured.Description("文档签署/出具日期，格式 yyyy-MM-dd，找不到填 null")
        LocalDate docDate,

        @dev.langchain4j.model.output.structured.Description("涉及金额（元），无金额填 null")
        Double amount,

        @dev.langchain4j.model.output.structured.Description("合同/文档涉及的相关方列表")
        List<Party> parties,

        @dev.langchain4j.model.output.structured.Description("文档关键条款/要点摘要，最多 5 条")
        List<String> keyPoints,

        @dev.langchain4j.model.output.structured.Description("风险点提示，没有风险返回空数组")
        List<String> risks,

        @dev.langchain4j.model.output.structured.Description("文档摘要，200字以内")
        String summary
    ) {}
}
