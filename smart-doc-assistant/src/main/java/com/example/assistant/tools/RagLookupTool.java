package com.example.assistant.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Metadata;

import java.util.List;

/**
 * 新增：知识库查询工具
 *
 * <p>让 Agent 能调用 Ch12 装配好的高级 RAG 管线（父子分块 + 混合检索 + 重排序）去查本地文档。
 * 这样 Agent 既能算数/看时间，也能"查资料"，形成多步推理：例如"帮我算一下退款流程里
 * 提到的 3 个工作日换算成小时是多少"——先调知识库工具拿到退款时效，再调计算器工具换算。
 *
 * <p>实现上直接复用 {@code RetrievalAugmentor} Bean，把检索结果拼成文本返回给 LLM。
 */
public class RagLookupTool {

    private final RetrievalAugmentor augmentor;

    public RagLookupTool(RetrievalAugmentor augmentor) {
        this.augmentor = augmentor;
    }

    @Tool("查询本地知识库，返回与问题最相关的文档片段，用于回答基于文档的问题")
    public String searchKnowledgeBase(@P("用户的问题") String question) {
        UserMessage um = UserMessage.from(question);
        AugmentationResult res = augmentor.augment(
                new AugmentationRequest(um, Metadata.from(um, "rag-tool", List.of())));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Content c : res.contents()) {
            sb.append("[片段 ").append(++i).append("] ")
              .append(c.textSegment().text()).append("\n\n");
        }
        return sb.toString().isBlank() ? "未检索到相关内容。" : sb.toString().strip();
    }
}
