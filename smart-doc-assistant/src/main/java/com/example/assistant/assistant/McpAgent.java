package com.example.assistant.assistant;

import dev.langchain4j.service.UserMessage;

/**
 * Ch17 新增：MCP Agent 接口
 *
 * <p>把 MCP 服务器暴露的工具注册给 Agent——Agent 就能调用"协议外部"的工具
 * （本地子进程 / 远程 HTTP 服务器），而不是只有项目内 {@code @Tool} 方法。
 *
 * <p><b>注意：本接口没有用 {@code @AiService} 注解。</b>
 * starter 的 {@code @AiService} 是「类路径扫描 + 无条件注册」的，一旦 MCP 关闭
 * （{@code chat.mcp.enabled=false}），它引用的 {@code toolProvider = "mcpToolProvider"}
 * 找不到 Bean，会直接让整个应用上下文启动失败——无法做到"MCP 可选"。
 * 因此改为在 {@link com.example.assistant.config.McpConfig} 里用
 * {@code AiServices.builder(...)} 显式构建，随 MCP 开关一起生效/跳过。
 */
public interface McpAgent {

    String ask(@UserMessage String question);
}
