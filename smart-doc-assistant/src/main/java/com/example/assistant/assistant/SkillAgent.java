package com.example.assistant.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * 新增：技能型 Agent 接口
 *
 * <p>通过 {@code Skills.toolProvider()} 把 SKILL.md 技能挂给 Agent：
 * 模型先看到技能清单（名称+描述），按需调用 {@code activate_skill} 激活，
 * 激活后技能指令（content）进入上下文、技能绑定的工具才可见（见 SkillConfig）。
 * @author hengdboy
 */
public interface SkillAgent {

    Result<String> chat(@MemoryId Long sessionId, @UserMessage String question);
}
