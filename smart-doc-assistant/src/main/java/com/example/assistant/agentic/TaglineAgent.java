package com.example.assistant.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 新增：并行映射的子 Agent（typed 接口）
 *
 * <p> 核实：parallelMapper 的子 Agent 必须用 <b>typed 接口</b>（方法签名是具体参数）。
 * 若用 UntypedAgent（{@code invoke(Map)} 签名），MapperAgentInvoker 会把集合元素当
 * 位置参数塞给 {@code invoke(Map)}，报 {@code argument type mismatch}——beta29 实验模块的真实缺陷。
 * @author hengdboy
 */
public interface TaglineAgent {

    @Agent("给单个主题生成一句卖点")
    @UserMessage("给主题生成一句 15 字以内的卖点，只输出卖点。主题：{{topic}}")
    String generate(@V("topic") String topic);
}
