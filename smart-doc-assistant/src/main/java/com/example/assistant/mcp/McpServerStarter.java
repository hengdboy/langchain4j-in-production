package com.example.assistant.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.example.assistant.config.AdvancedRagConfig;
import com.example.assistant.config.EmbeddingConfig;
import com.example.assistant.service.LexicalSegmentIndex;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.community.mcp.server.transport.StdioMcpServerTransport;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

/**
 * MCP 深度开发：stdio MCP Server 启动器。
 *
 * <p>独立可执行入口（{@code java -cp ... com.example.assistant.mcp.McpServerStarter}），
 * 由 MCP 客户端（如 Claude Desktop）以子进程方式拉起，通过 stdin/stdout 与客户端进行 JSON-RPC 通信。
 *
 * <h2>为什么不直接用 {@code SpringApplication.run()}</h2>
 * <ul>
 * <li>stdio Server 需要独占 stdin/stdout；Spring Boot 启动器会拉起 Tomcat、HTTP 客户端等额外组件，
 * 占用资源且无意义</li>
 * <li>MCP 子进程生命周期很短（用户每次启动 Claude Desktop 时启动一次），启动越快越好</li>
 * </ul>
 *
 * <h2>设计要点</h2>
 * <ul>
 * <li>用 {@link AnnotationConfigApplicationContext} 精准加载需要的配置类，
 * 跳过 Spring Boot 自动装配（无 Tomcat、无 web、无 actuator）</li>
 * <li>只注入 {@code EmbeddingModel} / {@code EmbeddingStore} / {@code RetrievalAugmentor} 三个 Bean，
 * 其他依赖（ChatModel / Controller / 等）一律不加载</li>
 * <li>把 {@link KnowledgeBaseMcpTools} 实例注册为 MCP Server 的工具 Bean，
 * 框架会扫描其 {@code @Tool} 注解自动生成 JSON Schema</li>
 * <li><strong>关键：日志必须走 {@code System.err}</strong>，否则会污染
 * {@code System.out} 上的 JSON-RPC 协议流，导致客户端断开（官方 SDK 文档警告）</li>
 * </ul>
 *
 * <h2>客户端配置示例（Claude Desktop）</h2>
 * <pre>{@code
 * {
 * "mcpServers": {
 * "smart-doc-assistant": {
 * "command": "java",
 * "args": ["-jar", "/path/to/smart-doc-assistant-0.16.0-SNAPSHOT-mcp.jar"]
 * }
 * }
 * }
 * }</pre>
 *
 * <p><b>必须用 {@code -jar}，不能用 {@code -cp xxx.jar 主类}</b>：Spring Boot 打成 fat jar 后，
 * 类在 {@code BOOT-INF/classes/}、依赖在 {@code BOOT-INF/lib/}，{@code -cp} 看不到它们。
 * 这个 jar 由 {@code mvn package -Pmcp} 产出（{@code spring-boot-maven-plugin} 以
 * {@code classifier=mcp} + {@code mainClass=McpServerStarter} 重新打包），
 * 走 {@code JarLauncher} 才能正确构造类加载器。
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
public final class McpServerStarter {

    private McpServerStarter() {}

    public static void main(String[] args) throws Exception {
        // 禁用 Spring Boot logging system（避免 Spring Boot 自动配置接管 Logback）
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
        // 编程式把 Logback root logger 重定向到 System.err（P0-5 修复）
        redirectLogbackToStderr();

        System.err.println("[smart-doc-assistant MCP Server] starting stdio transport...");

        // 精准加载 Spring 上下文：跳过 Spring Boot 全套自动装配
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StdioMcpServerTransport transport = null;
        try {
            ctx.register(AppConfig.class);
            // 把 application.yml 灌进 Environment：@Value 的代码默认值必须与配置默认值一致，
            // 否则子进程会悄悄用上另一套参数（例如 reranker 的 provider）
            loadClasspathYaml(ctx);
            ctx.refresh();

            // 取出核心 Bean
            EmbeddingModel embeddingModel = ctx.getBean(EmbeddingModel.class);
            EmbeddingStore<TextSegment> embeddingStore = ctx.getBean(EmbeddingStore.class);
            RetrievalAugmentor retrievalAugmentor = ctx.getBean(RetrievalAugmentor.class);

            // 通过工厂方法构造 MCP Server（避免与 McpServerConfig 重复装配代码——P2-4）
            McpServer server = McpServerFactory.create(embeddingModel, embeddingStore, retrievalAugmentor);
            transport = new StdioMcpServerTransport(server);

            System.err.println("MCP Server ready on stdio: smart-doc-assistant v0.17.0 (tools="
                    + KnowledgeBaseMcpTools.TOOL_NAMES + ")");

            // 阻塞主线程直到客户端断开；不再 try-with-resources 包 ctx，
            // 否则 awaitClose() 抛异常时 ctx.close() 可能不执行（P0-4 修复）
            transport.awaitClose();
            System.err.println("MCP Server shutdown complete.");
        } finally {
            // 显式按反顺序释放：先停 transport，再关 Spring ctx
            try {
                if (transport != null) {
                    transport.close();
                }
            } catch (Exception e) {
                System.err.println("Transport close failed: " + e.getMessage());
            }
            try {
                ctx.close();
            } catch (Exception e) {
                System.err.println("Context close failed: " + e.getMessage());
            }
        }
    }

    /**
     * 编程式把 Logback 输出重定向到 {@code System.err}。
     *
     * <p>为什么不用 logback.xml + 系统属性：stdio 启动器不依赖 Spring Boot 的 logging 初始化，
     * 直接用 {@code System.setProperty("logback.configurationFile", ...)} 会与 Spring Boot 冲突；
     * 编程式最可控。
     *
     * <p>后果：所有 SLF4J 调用（Jackson、LangChain4j、Spring Core 等）都走 stderr，
     * stdout 只剩 JSON-RPC 协议流，客户端解析不会被污染。
     */
    private static void redirectLogbackToStderr() {
        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();

            // 单一 stderr appender：人类可读 + 简洁
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
            // 日志配置失败不应阻止 MCP Server 启动；降级到 System.err.println
            System.err.println("Logback redirect failed, falling back to stderr println: " + t.getMessage());
        }
    }

    /**
     * 把 classpath 下的 {@code application.yml} 读进 Spring Environment。
     *
     * <p>{@link AnnotationConfigApplicationContext} 不会像 {@code SpringApplication} 那样自动加载
     * 配置文件，{@code @Value} 只能落到注解里写死的默认值——而这些默认值和 {@code application.yml}
     * 里的默认值可能已经漂移（历史上 reranker 就因此变成 dashscope 并因缺 key 启动失败）。
     * 这里手动加载一份，让子进程和 HTTP 模式读到同一套配置。
     */
    private static void loadClasspathYaml(AnnotationConfigApplicationContext ctx) {
        ClassPathResource resource = new ClassPathResource("application.yml");
        if (!resource.exists()) {
            System.err.println("application.yml not found on classpath, using @Value defaults");
            return;
        }
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application.yml", resource);
            sources.forEach(source -> ctx.getEnvironment().getPropertySources().addLast(source));
        } catch (Exception e) {
            System.err.println("Failed to load application.yml, using @Value defaults: " + e.getMessage());
        }
    }

    /**
     * 最小 Spring 上下文配置：组合业务所需的全部 @Configuration 类。
     * 用 {@link AnnotationConfigApplicationContext} 不走 Spring Boot 自动装配，
     * 仅加载需要的 @Configuration（不拉 Tomcat / 不拉 Actuator）。
     *
     * <p>刻意<b>不</b>导入 {@code AiServiceConfig}：MCP 工具只需要 EmbeddingModel / EmbeddingStore /
     * RetrievalAugmentor 三个 Bean，而 AiServiceConfig 会连带要求 ChatMemoryStore（默认 Redis）。
     * 子进程不该为了查知识库去连 Redis。
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Import({
            EmbeddingConfig.class,
            AdvancedRagConfig.class
    })
    static class AppConfig {

        /**
         * 关键词检索一路依赖的进程内索引。它在 HTTP 模式下来自 {@code @Component} 扫描，
         * 而这里没有组件扫描，必须显式声明一个空实例（MCP 子进程不做入库，索引为空，
         * 与向量一路做 RRF 融合时等价于只有向量结果）。
         */
        @Bean
        LexicalSegmentIndex lexicalSegmentIndex() {
            return new LexicalSegmentIndex();
        }
    }
}