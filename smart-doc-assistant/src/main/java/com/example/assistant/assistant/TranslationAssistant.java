package com.example.assistant.assistant;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 翻译助手
 *
 * 演示：
 * - @SystemMessage 从外部资源文件加载（fromResource）
 * - 多 @V 参数绑定到模板变量
 * @author hengdboy
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "openAiChatModel")   // ← 多模型 Bean 显式指定默认对话模型
public interface TranslationAssistant {

    /**
     * 文本翻译
     *
     * @param sourceLang 源语言（如"中文"）
     * @param targetLang 目标语言（如"英文"）
     * @param text       待翻译文本
     * @return 翻译结果
     */
    @SystemMessage(fromResource = "prompts/translator-system.txt")
    @UserMessage("翻译以下文本：\n{{text}}")
    String translate(@V("sourceLang") String sourceLang,
                     @V("targetLang") String targetLang,
                     @V("text") String text);
}
