package com.example.assistant.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionResult;
import com.example.assistant.assistant.McpAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Ch17 新增：MCP 客户端装配
 *
 * <p>链路（全部 javap 核实 1.19.0-beta29 真实 API）：
 * <pre>
 *   StdioMcpTransport（子进程通道）
 *     → DefaultMcpClient（协议客户端：握手/发现工具/执行工具）
 *       → McpToolProvider（把 MCP 工具转成 AiServices 能用的 ToolProvider）
 *         → @AiService(toolProvider = "mcpToolProvider")
 * </pre>
 *
 * <p>默认走 <b>stdio 传输</b>（本地子进程）连接配套的零依赖 demo-server.mjs；
 * 同时展示 <b>Streamable HTTP 传输</b>的构建方式（生产接远程 MCP 服务器用，默认不启用）。
 *
 * <h3>整体可开关（chat.mcp.enabled）</h3>
 * <p>MCP 依赖外部进程/远程服务，属于「不可控外部依赖」。默认 true；置为 false 时本类
 * 所有 Bean 都不创建，应用可在没有 node / 没有 MCP 服务器的环境下正常启动
 * （McpController 一并跳过注册，SkillConfig 的 mcp-tools 技能自动降级）。
 *
 * <pre>
 * # 临时关闭 MCP 启动（排查其他功能时很有用）
 * java -jar app.jar --chat.mcp.enabled=false
 * </pre>
 *
 * <h3>脚本路径解析（踩坑记录）</h3>
 * <p>{@code Path.toAbsolutePath()} 是相对 <b>JVM 工作目录</b>解析的。若在仓库根目录
 * （langchain4j-tutorial/）启动，相对路径 {@code mcp-server/demo-server.mjs} 会被解析到
 * 根目录下而非模块目录，导致 node 子进程立即退出；客户端随后干等握手超时，最终抛出
 * 与真实原因无关的 {@code mcpController} 依赖错误。
 * <p>因此这里做两件事：<b>多候选目录回退查找</b> + <b>启动前存在性校验</b>（fail-fast）。
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "chat.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpConfig {

    /** 相对路径的候选基准目录：依次尝试「当前工作目录」→「工作目录/smart-doc-assistant」。 */
    private static final List<String> SCRIPT_BASE_CANDIDATES = List.of("", "smart-doc-assistant");

    @Bean
    public McpTransport stdioMcpTransport(
            @Value("${chat.mcp.demo-server-command:node}") String nodeCommand,
            @Value("${chat.mcp.demo-server-script:mcp-server/demo-server.mjs}") String script) {
        // 启动本地子进程：node <脚本绝对路径>，通过 stdin/stdout 走 JSON-RPC
        String scriptPath = resolveScriptPath(script);
        log.info("🔌 启动 MCP stdio 传输: {} {}", nodeCommand, scriptPath);
        return StdioMcpTransport.builder()
                .command(List.of(nodeCommand, scriptPath))
                .logEvents(false)               // 置 true 可看协议流量（教学建议打开）
                .build();
    }

    /**
     * 把配置的脚本路径解析成绝对路径，并校验存在性。
     *
     * <p>绝对路径直接用；相对路径依次在候选基准目录下查找，命中即返回。
     * 全部未命中则 fail-fast 抛出可读错误——避免"等 30 秒超时后报一个看不懂的 Bean 错误"。
     */
    private String resolveScriptPath(String script) {
        Path configured = Path.of(script);

        if (configured.isAbsolute()) {
            if (Files.isRegularFile(configured)) {
                return configured.toString();
            }
            throw new IllegalStateException(buildNotFoundMessage(script, List.of(configured)));
        }

        Path workingDir = Path.of("").toAbsolutePath();
        List<Path> tried = SCRIPT_BASE_CANDIDATES.stream()
                .map(base -> base.isEmpty()
                        ? workingDir.resolve(configured)
                        : workingDir.resolve(base).resolve(configured))
                .map(Path::normalize)
                .toList();

        for (Path candidate : tried) {
            if (Files.isRegularFile(candidate)) {
                if (!candidate.equals(workingDir.resolve(configured).normalize())) {
                    // 命中回退目录：说明启动目录不是模块根，提示一下但不阻断
                    log.warn("⚠️  MCP 脚本未在当前工作目录找到，已回退定位到: {}（当前工作目录: {}）",
                            candidate, workingDir);
                }
                return candidate.toString();
            }
        }
        throw new IllegalStateException(buildNotFoundMessage(script, tried));
    }

    private String buildNotFoundMessage(String script, List<Path> tried) {
        return """
                MCP 服务端脚本未找到: %s
                已尝试路径:
                %s
                当前 JVM 工作目录: %s

                请任选一种方式修复：
                  1) 在模块目录下启动:  cd smart-doc-assistant && mvn spring-boot:run
                  2) 指定绝对路径:      --chat.mcp.demo-server-script=/abs/path/demo-server.mjs
                  3) 本次不需要 MCP:    --chat.mcp.enabled=false
                """.formatted(
                script,
                tried.stream().map(p -> "  - " + p).reduce((a, b) -> a + "\n" + b).orElse("  (无)"),
                Path.of("").toAbsolutePath());
    }

    /**
     * 远程 MCP 服务器用 Streamable HTTP 传输（javap 核实：StreamableHttpMcpTransport.builder().url(...)）。
     * 生产场景示例（接一个远程 MCP 服务器）；本项目配套的是本地 stdio demo，故默认不启用。
     */
    // @Bean
    public McpTransport streamableHttpMcpTransport() {
        return StreamableHttpMcpTransport.builder()
                .url("http://localhost:3001/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public McpClientListener mcpClientListener() {
        // Ch19：MCP 生命周期可观测——每次工具调用前后打审计日志（javap 核实：McpClientListener 全 default 方法）
        return new McpClientListener() {
            @Override
            public void beforeExecuteTool(McpCallContext ctx) {
                log.info("🔌 [MCP 审计] 即将执行: {}", ctx.message());
            }

            @Override
            public void afterExecuteTool(McpCallContext ctx, ToolExecutionResult result,
                                         Map<String, Object> extra) {
                log.info("🔌 [MCP 审计] 执行完成: {} → {}", ctx.message(),
                        result == null ? "null"
                                : result.resultText() != null
                                        ? result.resultText().length() > 60
                                                ? result.resultText().substring(0, 60) + "…"
                                                : result.resultText()
                                        : result.toString());
            }
        };
    }

    /**
     * MCP 协议客户端。
     *
     * <p>超时全部外部化：默认握手 5s（原默认 ~30s，本地 stdio 起不来时要干等半分钟才报错）。
     * javap 核实 Builder 支持 initializationTimeout / protocolDetectionTimeout /
     * toolExecutionTimeout / resourcesTimeout / promptsTimeout。
     */
    @Bean
    public McpClient mcpClient(
            McpTransport stdioMcpTransport,
            McpClientListener mcpClientListener,
            @Value("${chat.mcp.timeout.initialization:5s}") Duration initializationTimeout,
            @Value("${chat.mcp.timeout.tool-execution:20s}") Duration toolExecutionTimeout,
            @Value("${chat.mcp.timeout.resources:10s}") Duration resourcesTimeout) {
        return DefaultMcpClient.builder()
                .key("demo-server")               // 多客户端时靠 key 区分（toolNameMapper 用）
                .transport(stdioMcpTransport)
                .listener(mcpClientListener)      // Ch19：生命周期监听（审计/监控）
                .cacheToolList(true)              // 默认缓存工具列表；生产按需 evictToolListCache()
                .initializationTimeout(initializationTimeout)  // 握手超时：快速失败
                .toolExecutionTimeout(toolExecutionTimeout)
                .resourcesTimeout(resourcesTimeout)
                .build();
    }

    @Bean
    public McpToolProvider mcpToolProvider(McpClient mcpClient) {
        return McpToolProvider.builder()
                .mcpClients(mcpClient)
                // .filterToolNames("echo", "add", "get_time")   // 可只暴露部分工具
                .build();
    }

    /**
     * 显式构建 McpAgent（原先是 {@code @AiService} 注解式）。
     *
     * <p>改造原因：starter 的 {@code @AiService} 扫描是无条件的，注解里写死
     * {@code toolProvider = "mcpToolProvider"}；MCP 关闭后该 Bean 不存在，
     * 整个上下文会启动失败。放到本类中构建，即可跟随 {@code chat.mcp.enabled} 开关。
     */
    @Bean
    public McpAgent mcpAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                             McpToolProvider mcpToolProvider) {
        return AiServices.builder(McpAgent.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();
    }
}
