package com.example.assistant.service;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Ch09：图像理解服务。
 *
 * <p>让模型"看图说话"：把一张图片（URL 或 Base64）连同问题一起交给视觉大模型
 * qwen3.7-flash。核心是 LangChain4j 的多模态消息——{@link UserMessage} 不再只有文本，
 * 而是可以携带 {@link ImageContent}（图片）等 Content。
 *
 * <p>图片两种打开方式：
 * <ul>
 * <li>URL：{@code ImageContent.from(url)}，图片必须是公网可访问的 URL</li>
 * <li>Base64：{@code ImageContent.from(base64Data, "image/png")}，适合用户本地上传</li>
 * </ul>
 * @author hengdboy
 */
@Slf4j
@Service
public class VisionAnalysisService {

    private final ChatModel visionModel;

    public VisionAnalysisService(@Qualifier("visionChatModel") ChatModel visionModel) {
        this.visionModel = visionModel;
    }

    /** 通用图片问答：传图片 URL + 问题，返回模型回答 */
    public String analyzeByUrl(String imageUrl, String question) {
        String prompt = question == null || question.isBlank()
                ? "请详细描述这张图片的内容，包括其中的主体、文字、颜色和布局。"
                : question;
        UserMessage message = UserMessage.from(
                ImageContent.from(imageUrl),   // 方式一：公网 URL
                TextContent.from(prompt)
        );
        return visionModel.chat(message).aiMessage().text();
    }

    /** 通用图片问答：传 Base64 图片数据 + MIME 类型 + 问题 */
    public String analyzeByBase64(String base64Data, String mimeType, String question) {
        String prompt = question == null || question.isBlank()
                ? "请详细描述这张图片的内容，包括其中的主体、文字、颜色和布局。"
                : question;
        UserMessage message = UserMessage.from(
                ImageContent.from(base64Data, mimeType),  // 方式二：Base64
                TextContent.from(prompt)
        );
        return visionModel.chat(message).aiMessage().text();
    }
}
