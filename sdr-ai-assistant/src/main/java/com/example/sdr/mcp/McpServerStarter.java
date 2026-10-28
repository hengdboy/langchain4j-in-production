package com.example.sdr.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.example.sdr.config.SdrProperties;
import com.example.sdr.mcp.mock.FeishuMockClient;
import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.MailGateway;
import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.community.mcp.server.transport.StdioMcpServerTransport;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

/**
 * Ch24 v0.27 MCP：stdio 启动器（独立子进程入口）。
 *
 * <p>由 MCP 客户端（Claude Desktop / Cursor / Continue 等）以子进程方式拉起，
 * 通过 stdin/stdout 与客户端 JSON-RPC 通信。
 *
 * <h2>为什么不直接用 {@code SpringApplication.run()}</h2>
 * <ul>
 *   <li>stdio Server 需要独占 stdin/stdout；Spring Boot 启动器会拉起 Tomcat 等额外组件</li>
 *   <li>MCP 子进程生命周期短（用户每次启动 IDE 时启动一次），启动越快越好</li>
 * </ul>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>用 {@link AnnotationConfigApplicationContext} 精准加载需要的配置类，跳过 Spring Boot 自动装配</li>
 *   <li>只注入 MCP 工具链所需的依赖（mock 工具 + mapper + mock client）</li>
 *   <li>5 个工具类（共 6 个 {@code @Tool} 方法）注册为 MCP Server 工具 Bean，框架扫描 {@code @Tool} 自动生成 JSON Schema</li>
 *   <li><strong>日志必须走 {@code System.err}</strong>，否则污染 stdout 上的 JSON-RPC 协议流</li>
 * </ul>
 *
 * <h2>客户端配置示例（Claude Desktop）</h2>
 * <pre>{@code
 * {
 *   "mcpServers": {
 *     "sdr-ai-assistant": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/sdr-ai-assistant-0.23.0-SNAPSHOT-mcp.jar"]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p><b>必须用 {@code -jar}，不能用 {@code -cp xxx.jar 主类}</b>：fat jar 的类和依赖都在
 * {@code BOOT-INF/} 下，{@code -cp} 看不到。这个 jar 由 {@code mvn package -Pmcp} 产出。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public final class McpServerStarter {

    private McpServerStarter() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
        redirectLogbackToStderr();

        System.err.println("🚀 [sdr-ai-assistant MCP Server] starting stdio transport...");

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StdioMcpServerTransport transport = null;
        try {
            ctx.register(AppConfig.class);
            ctx.refresh();

            CustomsLookupTool customs = ctx.getBean(CustomsLookupTool.class);
            SmtpVerifyTool smtp = ctx.getBean(SmtpVerifyTool.class);
            KpFinderTool kp = ctx.getBean(KpFinderTool.class);
            MailGateway mail = ctx.getBean(MailGateway.class);
            FeishuMockClient feishu = ctx.getBean(FeishuMockClient.class);
            // SdrProperties 触发注册（@EnableConfigurationProperties 在 SdrApplication 主类已处理，
            // 这里手动 getBean 验证配置可用）
            ctx.getBean(SdrProperties.class);

            McpServer server = McpServerFactory.create(customs, smtp, kp, mail, feishu);
            transport = new StdioMcpServerTransport(server);

            System.err.println("✅ MCP Server ready on stdio: " + McpServerFactory.SERVER_NAME
                    + " v" + McpServerFactory.SERVER_VERSION + " (tools=" + McpServerFactory.TOOL_NAMES + ")");

            transport.awaitClose();
            System.err.println("👋 MCP Server shutdown complete.");
        } finally {
            try { if (transport != null) transport.close(); }
            catch (Exception e) { System.err.println("⚠️ Transport close failed: " + e.getMessage()); }
            try { ctx.close(); }
            catch (Exception e) { System.err.println("⚠️ Context close failed: " + e.getMessage()); }
        }
    }

    /** 把 Logback 输出重定向到 {@code System.err}，避免污染 stdout 上的 JSON-RPC */
    private static void redirectLogbackToStderr() {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();
            ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
            appender.setContext(loggerContext);
            appender.setTarget("System.err");
            appender.setName("STDERR");
            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(loggerContext);
            encoder.setPattern("%d{HH:mm:ss.SSS} %-5level [%logger{20}] %msg%n");
            encoder.start();
            appender.setEncoder(encoder);
            appender.start();
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.WARN);
            rootLogger.addAppender(appender);
        } catch (Throwable t) {
            System.err.println("⚠️ Logback redirect failed: " + t.getMessage());
        }
    }

    /**
     * 最小 Spring 上下文：只扫工具包和 mock 包。
     *
     * <p><b>不要扫整个 {@code com.example.sdr.config}</b>：那里有 {@code SdrAgentConfig}，
     * 它会连带要求 CostChatModelListener / Guardrail / ChatModel 等一整条 Agent 链路，
     * 子进程只为暴露 6 个工具，没必要也不该拉起这些。{@link SdrProperties} 单独用
     * {@code @EnableConfigurationProperties} 注册即可。
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(basePackages = {
            "com.example.sdr.tools",
            "com.example.sdr.mcp.mock"
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties(SdrProperties.class)
    static class AppConfig {
    }
}