package com.example.assistant.agentic;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 新增：顺序工作流（sequence）演示——内容生产流水线
 *
 * <p>官方 {@code langchain4j-agentic} 实验模块（源码核实：beta29 存在、1.19.0 稳定版 404）。
 * 用 {@code AgenticServices.agentBuilder()} 建 3 个无类型 Agent，再用
 * {@code sequenceBuilder()} 串联：<b>撰写 → 精简 → 定稿</b>，前一个 Agent 的
 * 输出（outputKey）作为后一个 Agent 的输入（{{占位符}} 同名变量）。
 *
 * <p>这是"确定性工作流"：步骤固定、顺序固定，与 Supervisor（自主编排）互补。
 */
@Service
public class ContentPipelineService {

    private final UntypedAgent pipeline;

    public ContentPipelineService(@Qualifier("openAiChatModel") ChatModel chatModel) {
        // ① 撰写员：主题 → 3 句产品介绍初稿
        UntypedAgent writer = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("writer")
                .description("根据主题撰写一段 3 句产品介绍初稿")
                .userMessage("你是文案撰写员。根据主题写一段不超过 3 句的产品介绍，只输出介绍本身。主题：{{topic}}")
                .inputKey(String.class, "topic")     // topic 取自 AgenticScope 同名变量
                .outputKey("draft")                   // 结果写入 draft 变量
                .build();

        // ② 精简员：初稿 → 一句话卖点
        UntypedAgent editor = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("editor")
                .description("把初稿精简成一句卖点")
                .userMessage("你是文案编辑。把下面的初稿精简成一句话卖点，只输出结果。\n初稿：{{draft}}")
                .inputKey(String.class, "draft")
                // 读上游写入的 draft
                .outputKey("final")
                .build();

        // ③ 顺序流水线：writer → editor，最终取 final 变量
        pipeline = AgenticServices.sequenceBuilder()
                .subAgents(writer, editor)
                .outputKey("final")
                .build();
    }

    /** 跑一遍流水线：主题 → 最终一句话卖点 */
    public String run(String topic) {
        Object result = pipeline.invoke(Map.of("topic", topic));
        return result == null ? "（流水线未产出结果）" : String.valueOf(result);
    }
}
