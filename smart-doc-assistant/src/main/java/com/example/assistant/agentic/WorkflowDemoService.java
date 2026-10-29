package com.example.assistant.agentic;

import com.example.assistant.tools.CalculatorTools;
import com.example.assistant.tools.RagLookupTool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 新增：四种工作流模式演示（官方 langchain4j-agentic 实验模块）
 *
 * <p>除顺序 sequence（ContentPipelineService）与自主 Supervisor（CustomerServiceSupervisor）外，
 * 官方还提供四种<b>确定性工作流</b>，这里全部用真实代码落地并逐一讲解：
 * <ul>
 * <li>{@code parallelBuilder()}  并行：多个独立 Agent 同时跑，结果合并（executor 可选）</li>
 * <li>{@code loopBuilder()}      循环：评分→修改→再评分，直到退出条件或 maxIterations</li>
 * <li>{@code conditionalBuilder()} 条件：按谓词从多个子 Agent 里选一个执行</li>
 * <li>{@code parallelMapperBuilder()} 并行映射：对集合每个元素并行执行同一子 Agent</li>
 * </ul>
 *
 * <p>所有 API 均 源码核实（ParallelAgentService / LoopAgentService /
 * ConditionalAgentService / ParallelMapperService，公共方法见下）。
 * @author hengdboy
 */
@Service
public class WorkflowDemoService {

    private final ChatModel chatModel;
    private final RagLookupTool ragLookupTool;
    private final CalculatorTools calculatorTools;

    private UntypedAgent parallelWorkflow;   // 并行
    private UntypedAgent loopWorkflow;       // 循环
    private UntypedAgent conditionalWorkflow;// 条件
    private BatchTaglineAgent mapperWorkflow;// 并行映射（typed 顶层接口）

    public WorkflowDemoService(@Qualifier("openAiChatModel") ChatModel chatModel,
                               RagLookupTool ragLookupTool,
                               CalculatorTools calculatorTools) {
        this.chatModel = chatModel;
        this.ragLookupTool = ragLookupTool;
        this.calculatorTools = calculatorTools;
        buildParallel();
        buildLoop();
        buildConditional();
        buildMapper();
    }

    // ===== 并行：查知识库 + 算折扣 同时进行，结果合并 =====

    private void buildParallel() {
        UntypedAgent faqAgent = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("faq-agent")
                .description("查知识库回答政策问题")
                .userMessage("查知识库回答下面的问题，只输出答案。问题：{{q}}")
                .inputKey(String.class, "q")
                .outputKey("policy")
                .tools(ragLookupTool)
                .build();

        UntypedAgent calcAgent = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("calc-agent")
                .description("执行数值计算")
                .userMessage("执行下面的计算，只输出结果。计算：{{q}}")
                .inputKey(String.class, "q")
                .outputKey("price")
                .tools(calculatorTools)
                .build();

        parallelWorkflow = AgenticServices.parallelBuilder()
                .subAgents(faqAgent, calcAgent)                       // 两个独立 Agent 并行
                .output(scope -> "【政策】" + scope.readState("policy")
                        + "\n【价格】" + scope.readState("price"))      // 合并两个输出
                .build();
    }

    /** 并行模式：q1 查政策、q2 算价格，同时执行 */
    public String runParallel(String q1, String q2) {
        Object result = parallelWorkflow.invoke(Map.of("q", q1 + " " + q2));
        return result == null ? "（无输出）" : String.valueOf(result);
    }

    // ===== 循环：评分 → 修改 → 再评分，直到 score≥8 或最多 3 轮 =====

    private void buildLoop() {
        UntypedAgent reviewer = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("reviewer")
                .description("给文本打 0-10 分")
                .userMessage("给下面的文本打 0-10 分，只输出数字。文本：{{text}}")
                .inputKey(String.class, "text")
                .outputKey("score")
                .build();

        UntypedAgent editor = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("editor")
                .description("根据分数改进文本")
                .userMessage("你给文本打了 {{score}} 分。请改进下面文本使其更好，只输出改进后的文本。原文：{{text}}")
                .inputKeys(String.class, "text", String.class, "score")
                .outputKey("text")                                    // 覆盖 text，供下一轮评分
                .build();

        loopWorkflow = AgenticServices.loopBuilder()
                .subAgents(reviewer, editor)                          // 每轮：先评分再修改
                .maxIterations(3)
                .testExitAtLoopEnd(true)                              // 本轮结束再检查退出条件
                .exitCondition(scope -> {                             // score ≥ 8 就退出
                    Object s = scope.readState("score");
                    try {
                        return Integer.parseInt(String.valueOf(s)) >= 8;
                    } catch (Exception e) {
                        return false;                                 // 解析失败 → 继续
                    }
                })
                .outputKey("text")
                .build();
    }

    /** 循环模式：给一段初稿，反复"评分→修改"直到达标或 3 轮 */
    public String runLoop(String initialText) {
        Object result = loopWorkflow.invoke(Map.of("text", initialText, "score", "0"));
        return result == null ? "（无输出）" : String.valueOf(result);
    }

    // ===== 条件：按问题关键词选专家 =====

    private void buildConditional() {
        UntypedAgent refundExpert = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("refund-expert")
                .description("退款/退货政策专家")
                .userMessage("你是退款政策专家，查知识库回答：{{q}}")
                .inputKey(String.class, "q")
                .outputKey("answer")
                .tools(ragLookupTool)
                .build();

        UntypedAgent techExpert = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name("tech-expert")
                .description("技术/计算专家")
                .userMessage("你是技术专家，用工具计算并回答：{{q}}")
                .inputKey(String.class, "q")
                .outputKey("answer")
                .tools(calculatorTools)
                .build();

        conditionalWorkflow = AgenticServices.conditionalBuilder()
                .subAgents(scope -> containsAny(scope, "退款", "退货", "运费"), refundExpert)
                .subAgents(scope -> containsAny(scope, "计算", "折扣", "多少", "几折"), techExpert)
                .outputKey("answer")
                .build();
    }

    /** 条件模式：q 含退款/退货关键词 → 退款专家；含计算/折扣 → 技术专家 */
    public String runConditional(String q) {
        Object result = conditionalWorkflow.invoke(Map.of("q", q));
        return result == null ? "（无输出）" : String.valueOf(result);
    }

    private boolean containsAny(dev.langchain4j.agentic.scope.AgenticScope scope, String... keywords) {
        String q = String.valueOf(scope.readState("q"));
        for (String k : keywords) {
            if (q.contains(k)) return true;
        }
        return false;
    }

    // ===== 并行映射：对一组主题并行生成卖点 =====

    private void buildMapper() {
        // 子 Agent 与顶层都必须用 typed 接口（UntypedAgent 在 beta29 报 argument type mismatch）
        TaglineAgent taglineAgent = AgenticServices.agentBuilder(TaglineAgent.class)
                .chatModel(chatModel)
                .name("tagline-agent")
                .description("给单个主题生成一句卖点")
                .outputKey("tagline")
                .build();

        mapperWorkflow = AgenticServices.parallelMapperBuilder(BatchTaglineAgent.class)
                .subAgents(taglineAgent)
                .itemsProvider("topics")                              // scope 里 topics 是待迭代集合
                .build();
    }

    /** 并行映射：topics 列表逐项生成卖点（并行） */
    public Object runMapper(List<String> topics) {
        List<String> result = mapperWorkflow.generateTaglines(topics);
        return result == null ? "（无输出）" : result;
    }
}
