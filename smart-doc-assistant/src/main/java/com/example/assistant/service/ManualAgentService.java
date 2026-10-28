package com.example.assistant.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新增：手动 Agent 循环（教学用，揭开框架黑盒）
 *
 * <p>{@code AiServices} 在底层就是跑下面这个循环。这里用最基础的 API 把它写出来，
 * 让你看清"Agent = 大模型 + 工具执行 + 记忆"到底发生了什么：
 * <pre>
 * 1. 把用户问题作为 UserMessage 加入消息列表；
 * 2. 调 chatModel.chat(request) 得到 AiMessage——注意 request 里必须带 toolSpecifications，
 * 否则模型根本不知道有哪些工具可用，永远不会发出工具调用请求（2026-10-28 修复的 bug）；
 * 3. 若 AiMessage 含 toolExecutionRequests → 用 DefaultToolExecutor 逐个执行工具，
 * 把结果包成 ToolExecutionResultMessage 放回消息列表，回到第 2 步；
 * 4. 若没有工具请求 → 这就是最终答案。
 * </pre>
 *
 * <p>工具名 → 执行器 的映射在构造时扫描每个工具对象的 {@code @Tool} 方法建立。
 * （注：{@code DefaultToolExecutor(Object, ToolExecutionRequest)} 的便利构造是按"方法名"反射查找，
 * 当 {@code @Tool} 显式指定了 name 时可能匹配不上；所以这里按"工具名 = @Tool.name() 或方法名"
 * 自己建映射，再用 {@code DefaultToolExecutor(Object, Method)} 精确绑定，最稳。）
 */
@Slf4j
public class ManualAgentService {

    private final ChatModel chatModel;
    private final Map<String, DefaultToolExecutor> executors = new HashMap<>();
    private final List<ToolSpecification> toolSpecifications = new ArrayList<>();
    private final Map<Object, List<ChatMessage>> memory = new ConcurrentHashMap<>();
    private static final int MAX_ROUNDS = 10;

    public ManualAgentService(ChatModel chatModel, List<Object> tools) {
        this.chatModel = chatModel;
        for (Object tool : tools) {
            toolSpecifications.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
            for (Method m : tool.getClass().getDeclaredMethods()) {
                Tool t = m.getAnnotation(Tool.class);
                if (t != null) {
                    String name = (t.name() == null || t.name().isBlank()) ? m.getName() : t.name();
                    executors.put(name, new DefaultToolExecutor(tool, m));
                }
            }
        }
    }

    public String run(Object sessionId, String question) {
        List<ChatMessage> messages = memory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        messages.add(UserMessage.from(question));

        for (int round = 0; round < MAX_ROUNDS; round++) {
            // 工具清单必须每轮随请求下发——框架 14.4.3 的动作，手动版自己补
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecifications)
                    .build();
            ChatResponse resp = chatModel.chat(request);
            AiMessage ai = resp.aiMessage();

            // 模型不再请求工具 → 这就是最终答案
            if (ai.toolExecutionRequests().isEmpty()) {
                return ai.text();
            }

            // 把"带工具请求的 AiMessage"加入历史，再逐个执行工具、把结果追加回去
            messages.add(ai);
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                DefaultToolExecutor ex = executors.get(req.name());
                String result = (ex == null)
                        ? "未知工具：" + req.name()
                        : ex.execute(req, sessionId);
                messages.add(ToolExecutionResultMessage.from(req, result));
                log.info("手动循环第{}轮执行工具[{}] → {}", round + 1, req.name(), result);
            }
        }
        return "（已达到最大工具轮次 " + MAX_ROUNDS + "，未产出最终答案）";
    }
}
