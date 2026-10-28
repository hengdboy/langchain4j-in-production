package com.example.lab;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;


/**
 * Lab2~5 共用的离线桩模型：按脚本顺序回放预设回复。
 *
 * <p>为什么桩值得写而不是用真模型：①测试可离线、可重复、零成本；
 * ②能精确构造"模型最可能犯的错"（脏 JSON、多余工具调用），把归一化/护栏
 * 逼到边界上测——真模型的输出你控制不了。
 *
 * <p>只重写 {@code chat(ChatRequest)} 一个方法：AiServices 内部经
 * ChatExecutor 最终调的就是它（源码 DirectChatExecutor L29，javap/源码双核实）。
 * 脚本条目支持两种形态：纯文本、或 @@TOOL@@id|name|json 形态的工具调用。
 */
public final class StubChatModel implements ChatModel {

    public static final String TOOL_CALL_MARKER = "@@TOOL@@";

    private final Queue<String> scripted = new ConcurrentLinkedQueue<>();
    private final Function<ChatRequest, String> fallback;

    /** 按顺序回放脚本；耗尽即抛错（脚本写少了立刻暴露，不静默）。 */
    public StubChatModel(String... replies) {
        this(req -> { throw new IllegalStateException("脚本耗尽，收到的请求：" + req.messages()); });
        for (String r : replies) scripted.add(r);
    }

    public StubChatModel(Function<ChatRequest, String> fallback) {
        this.fallback = fallback;
    }

    /** 便利方法：把一条纯文本回复排进脚本（工具调用之后的最终答复）。 */
    public StubChatModel enqueueText(String reply) {
        scripted.add(reply);
        return this;
    }

    /** 便利方法：把一次工具调用请求排进脚本。 */
    public StubChatModel enqueueToolCall(String id, String name, String argumentsJson) {
        scripted.add(TOOL_CALL_MARKER + id + "|" + name + "|" + argumentsJson);
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String next = scripted.isEmpty() ? fallback.apply(request) : scripted.poll();
        AiMessage ai;
        if (next.startsWith(TOOL_CALL_MARKER)) {
            String[] parts = next.substring(TOOL_CALL_MARKER.length()).split("\\|", 3);
            ai = AiMessage.from(ToolExecutionRequest.builder()
                    .id(parts[0]).name(parts[1]).arguments(parts[2]).build());
        } else {
            ai = AiMessage.from(next);
        }
        return ChatResponse.builder().aiMessage(ai).tokenUsage(new TokenUsage(10, 10)).build();
    }
}
