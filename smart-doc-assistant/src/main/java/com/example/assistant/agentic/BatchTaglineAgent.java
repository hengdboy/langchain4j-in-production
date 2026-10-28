package com.example.assistant.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * 新增：并行映射（parallelMapper）的 typed 顶层接口
 *
 * <p> 核实：beta29 中 <b>UntypedAgent 顶层的 parallelMapper 有缺陷</b>（实测
 * {@code invoke(Map)} 报 argument type mismatch），官方示例的顶层一律是 <b>typed 接口</b>。
 * 所以这里用 {@code @Agent} 注解接口 + {@code parallelMapperBuilder(Class)} 模式。
 */
public interface BatchTaglineAgent {

    @Agent("给一组主题并行生成一句卖点")
    @UserMessage("为下面的主题列表逐个生成一句 15 字以内的卖点，按顺序输出。主题：{{topics}}")
    List<String> generateTaglines(@V("topics") List<String> topics);
}
