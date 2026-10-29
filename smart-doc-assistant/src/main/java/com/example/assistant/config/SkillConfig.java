package com.example.assistant.config;

import com.example.assistant.assistant.SkillAgent;
import com.example.assistant.tools.RagLookupTool;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 新增：Skills 装配（官方 langchain4j-skills 实验模块，源码核实 API）
 *
 * <p>链路：
 * <pre>
 * ClassPathSkillLoader.loadSkills("skills")   // 从 classpath 加载 SKILL.md
 * → Skills.from(...)                        // 包装成技能集
 * → skills.toolProvider()                 // 转成 AiServices 可用的 ToolProvider
 * → AiServices.toolProvider(...)        // activate_skill / read_skill_resource 工具
 * </pre>
 *
 * <p>refund-policy 技能绑定了 {@code RagLookupTool}（skill-scoped tool）：只有该技能被激活后，
 * 工具才对模型可见——"技能激活 = 工具解锁"，保持工具列表精简。
 * @author hengdboy
 */
@Configuration
@Slf4j
public class SkillConfig {

    /**
     * @param mcpToolProvider MCP 工具提供者。用 {@link ObjectProvider} 软依赖：
     * {@code chat.mcp.enabled=false} 时该 Bean 不存在，
     * mcp-tools 技能自动降级为「纯文本技能」，Skills 其余功能不受影响。
     */
    @Bean
    public Skills appSkills(RagLookupTool ragLookupTool,
                            ObjectProvider<McpToolProvider> mcpToolProvider) {
        // 从 classpath 加载 skills/ 下的所有 SKILL.md
        List<Skill> loaded = ClassPathSkillLoader.loadSkills("skills");
        log.info("加载到 {} 个技能: {}", loaded.size(),
                loaded.stream().map(Skill::name).toList());

        McpToolProvider mcp = mcpToolProvider.getIfAvailable();

        // 给技能绑定 skill-scoped 工具（编程式重建，源码核实 Skill.builder()）
        List<Skill> enhanced = loaded.stream().map(s -> {
            if ("refund-policy".equals(s.name())) {
                return Skill.builder()
                        .name(s.name())
                        .description(s.description())
                        .content(s.content())
                        .tools(ragLookupTool)        // 激活 refund-policy 后才可见
                        .build();
            }
            if ("mcp-tools".equals(s.name())) {
                if (mcp == null) {
                    log.warn("MCP 已禁用（chat.mcp.enabled=false），技能 [mcp-tools] 降级为纯文本技能（不挂载工具）");
                    return s;
                }
                return Skill.builder()
                        .name(s.name())
                        .description(s.description())
                        .content(s.content())
                        .toolProviders(mcp)               // 激活 mcp-tools = 挂载 MCP 工具
                        .build();
            }
            return s;
        }).toList();

        return Skills.from(enhanced);
    }

    @Bean
    public SkillAgent skillAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                                 ChatMemoryProvider chatMemoryProvider,
                                 Skills appSkills) {
        return AiServices.builder(SkillAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .toolProvider(appSkills.toolProvider())          // activate_skill / read_skill_resource
                .systemMessage("你是一个客服助手。可用技能如下：\n"
                        + appSkills.formatAvailableSkills()       // 技能清单（名称+描述）
                        + "\n当用户请求与某个技能相关时，先调用 activate_skill 工具激活它，"
                        + "再严格按该技能的指令执行。")
                .maxToolCallingRoundTrips(10)
                .build();
    }
}
