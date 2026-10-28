package com.example.sdr.agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * Ch24 阶段一 Agent：线索挖掘（Google Dorks 生成）。
 *
 * <h2>为什么是单方法接口（关键）</h2>
 * <p>{@code AgenticServices.agentBuilder(Class)} 校验接口内<b>恰好一个</b>
 * {@code @Agent} 方法（javap 核实 {@code AgentUtil.validateAgentClass}：
 * 多个 {@code @Agent} 方法抛 {@code Multiple agent methods found}）。
 * 因此每个 Agent = 一个单方法接口；询盘评估拆到 {@link InquiryAssessorAgent}。
 *
 * <h2>为什么用 {@code @Agent} 而不是 {@code @AiService}</h2>
 * <ul>
 *   <li>✅ {@code name} — Supervisor 识别 Agent 的唯一标识（缺省取方法名）</li>
 *   <li>✅ {@code value/description} — 其他 Agent 通过描述决定是否调用</li>
 *   <li>✅ {@code outputKey} — 自动写入 AgenticScope 黑板（跨 Agent 通信）</li>
 *   <li>✅ 可被 {@code supervisorBuilder()} / {@code sequenceBuilder()} 编排</li>
 * </ul>
 *
 * <h2>编排位置</h2>
 * <pre>
 *   TradeSupervisor (编排)
 *     ├─ LeadSearchAgent    （阶段一，outputKey="dorks"）
 *     ├─ ...
 * </pre>
 *
 * @author langchain4j-tutorial
 * @since v0.24
 */
public interface LeadSearchAgent {

    /**
     * 给定目标市场和产品画像，生成 3-5 条 Google Dorks 搜索指令。
     */
    @SystemMessage("""
            你是 B2B 外贸线索挖掘专家。给定目标市场和产品画像，生成 3-5 条 Google Dorks 搜索指令。
            规则：
            1. 每条指令要包含 site: 限定（领英/海关/官网/Google Patents）
            2. 必须包含目标职级关键词（CEO / Procurement / Supply Chain / VP）
            3. 必须包含产品行业关键词
            4. 用 inurl:linkedin.com 等精确限定 URL
            5. 至少 1 条用于领英、1 条用于海关数据
            """)
    @UserMessage("""
            目标市场：{{market}}
            产品：{{product}}
            关键词：{{keywords}}
            请生成 3-5 条 Google Dorks。
            """)
    @Agent(value = "根据目标市场和产品画像生成 Google Dorks 搜索指令",
           outputKey = "dorks")
    List<String> generateDorks(@V("market") String market,
                              @V("product") String product,
                              @V("keywords") String keywords);
}
