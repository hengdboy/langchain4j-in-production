package com.example.assistant.assistant;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * ：基于检索增强的文档问答助手（v0.10）。
 *
 * <p>通过 {@code retrievalAugmentor} 接入  的高级 RAG 管线（父子分块 + 混合检索 + 重排序），
 * 框架会自动把检索到的 Content 注入到 Prompt，再交给 LLM 作答。业务侧只写一句接口方法。
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        retrievalAugmentor = "advancedRetrievalAugmentor"
)
public interface DocQaAssistant {

    @SystemMessage("你是智能文档助手，只依据下方提供的参考资料用简洁中文回答；" +
            "如果资料里没有相关信息，明确说「资料中未提及」，不要编造。")
    Result<String> ask(String question);
}
