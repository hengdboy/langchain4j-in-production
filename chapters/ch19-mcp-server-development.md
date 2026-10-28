# Ch19 MCP 深度开发——把智能体能力暴露给整个生态

> **本章基线**：项目 `smart-doc-assistant` ｜ 端口 `8090` ｜ LangChain4j `1.22.0` + 实验模块 `1.22.0-beta32` ｜ JDK `21` ｜ 最后验证 `2026-10-28`

> 本章目标：
>
> 1. 理解为什么需要"造自己的 MCP Server"：Ch17 是用别人的，本章是造自己的
> 2. 掌握一个**关键事实**：LangChain4j 1.22.0 核心模块**没有** Server SDK，必须用社区包 `langchain4j-community-mcp-server`（官方文档明确指引，本章实测验证）
> 3. 掌握**两种传输**的实战：stdio（Claude Desktop 子进程方式）+ Streamable HTTP（Spring Boot 嵌入式）
> 4. 掌握**官方 SDK 的局限**：只支持 Tools 原语（`@Tool`）；本章自实现 `McpJsonRpcDispatcher` 把 Resources / Prompts 也接上
> 5. 掌握**安全与审计**（呼应 Ch16）：API Key 鉴权 + 工具白名单 + 调用审计 + 敏感字段 redact
> 6. 记录一组**P0 工程修复**：JSON-RPC id 类型保留、空 API Key 万能 Key 漏洞、stdio 日志污染协议流、审计无上限
> 7. 理解**配置即代码**的真相源设计：`KnowledgeBaseMcpTools.TOOL_NAMES` 单一来源驱动白名单

---

## 19.1 为什么需要自建 MCP Server

Ch17 讲的是**用**别人的 MCP Server（Node.js 写的 demo-server.mjs，stdio 传输）。本章讲的是**造**自己的 MCP Server——把 Java/Spring Boot 项目的能力（RAG / 数据库 / 内部 API）暴露为标准协议，让任何 MCP 客户端都能用：

```
                       ┌─────────────────────────────────────┐
                       │  你的业务能力                       │
                       │  ├─ RAG 知识库（Ch08/Ch10-Ch13） │
                       │  ├─ Agent 工具集（Ch06/Ch14）      │
                       │  ├─ Skills 技能库（Ch18）          │
                       │  └─ 内部 API（业务系统）           │
                       └─────────────┬───────────────────────┘
                                     │ MCP 协议（JSON-RPC 2.0）
                                     ↓
              ┌──────────────────────────────────────────────┐
              │ 你的 MCP Server（本章 v0.17 新增）           │
              │ ├─ 3 个 @Tool：searchKnowledgeBase 等        │
              │ ├─ 4 个 Resource：docs:// 协议              │
              │ ├─ 3 个 Prompt：review-code 等模板          │
              │ └─ 安全：API Key + 工具白名单                │
              └─────────────┬────────────────────────────────┘
                            │
       ┌────────────────────┼────────────────────┐
       ↓                    ↓                    ↓
  Claude Desktop      Cursor IDE       其他 LangChain4j 客户端
  (stdio 子进程)      (stdio)         (Streamable HTTP)
```

**三种典型业务场景**：

| 场景 | 价值 |
|---|---|
| 企业内知识库对全公司开放 | 把 Confluence / Notion / 内部文档包装为 MCP Server，员工在 Claude/Cursor 里直接问 |
| AI 产品矩阵互操作 | 一个产品的 Agent 可以调用另一个产品暴露的 MCP Server，避免重复造轮子 |
| 调试与可观测性 | 把内部工具的调用记录、token 用量通过 MCP 协议统一暴露给运维仪表盘 |

> 💡 **Spring AI 对比**：Spring AI 1.0 同样支持 MCP，但 Server 端主要走 Spring AI MCP Server 扩展（基于 `spring-ai-starter-mcp-server`），绑定 Spring WebFlux。LangChain4j 的方案更轻——stdio 模式不需要任何 Web 框架，HTTP 模式直接复用你已学的 Spring MVC。两种生态在协议层完全兼容。

---

## 19.2 关键事实：官方 SDK 的位置（实测验证）

写本章前必须先回答：**LangChain4j 到底有没有 MCP Server SDK？**

**官方文档**（https://docs.langchain4j.info/tutorials/mcp-stdio-server ）明确指出：

> LangChain4j provides an MCP **client** (`langchain4j-mcp`) for connecting to MCP servers.  
> If you want to build a Java-based MCP **stdio server** (a local subprocess launched by an MCP client),  
> use the community module: `langchain4j-community-mcp-server`.

**实测**（写代码前的核实——方法论铁律）：

```bash
# 1. 核心模块 langchain4j-mcp: 173 个 class，全部 client/transport/protocol/registry 侧，无 server 包
$ jar tf langchain4j-mcp-1.22.0-beta32.jar | grep -c '\.class$'
173

$ jar tf langchain4j-mcp-1.22.0-beta32.jar | grep -i server
dev/langchain4j/mcp/client/McpServerInfo.class                  # client 包里的"服务器信息"消息
dev/langchain4j/mcp/protocol/McpServerDiscoverParams.class
dev/langchain4j/mcp/protocol/McpServerDiscoverRequest.class     # 1.22 新增：MCP Registry 发现协议
dev/langchain4j/mcp/protocol/McpServerDiscoverResponse.class
dev/langchain4j/mcp/protocol/McpServerDiscoverResponse$Result.class
dev/langchain4j/mcp/protocol/McpServerMethod.class
dev/langchain4j/mcp/registryclient/model/McpGetServerResponse.class
dev/langchain4j/mcp/registryclient/model/McpServer.class        # Registry 里的服务器条目，不是 Server SDK
dev/langchain4j/mcp/registryclient/model/McpServerList.class
dev/langchain4j/mcp/registryclient/model/McpServerListRequest.class
dev/langchain4j/mcp/registryclient/model/McpServerListRequest$Builder.class
# 11 条全部命中 "server" 关键字，但没有一条是 Server SDK
$ jar tf langchain4j-mcp-1.22.0-beta32.jar | grep -c 'mcp/server/'
0

# 2. 社区模块 langchain4j-community-mcp-server: 4 个 class（3 个顶层 + 1 个匿名内部类），就是 Server SDK
$ jar tf langchain4j-community-mcp-server-1.22.0-beta32.jar | grep '\.class$'
dev/langchain4j/community/mcp/server/McpServer$1.class          # 匿名内部类
dev/langchain4j/community/mcp/server/McpServer.class
dev/langchain4j/community/mcp/server/McpToolSchemaMapper.class
dev/langchain4j/community/mcp/server/transport/StdioMcpServerTransport.class
```

**结论**：
- ✅ 官方 Server SDK 在**社区模块** `dev.langchain4j:langchain4j-community-mcp-server`（走 beta 版本线，1.22.0-beta32）
- ⚠️ 1.22.0-beta32 这版**仍然只支持 stdio 传输**（`StdioMcpServerTransport`），无官方 HTTP 实现——从 1.19 到 1.22，这几个类一直没变
- ⚠️ **只支持 Tools 原语**（`@Tool` 注解），无 `@McpResource` / `@McpPrompt`——Resources/Prompts 要自实现

> ⚠️ **坑点**：写本章前我差点下了"LangChain4j 没有 Server SDK" 的错误结论。**实测 + 看官方文档原文** 才发现是社区包而不是核心包。**永远不要凭印象写 API**——这是你的方法论里"写代码前先核实"的核心铁律。

---

## 19.3 协议回顾（30 秒）

MCP 是 JSON-RPC 2.0 over stdio 或 Streamable HTTP，Ch17 已讲过。本章只列骨架，便于后续引用：

```mermaid
sequenceDiagram
    participant C as MCP Client
    participant S as MCP Server

    Note over C,S: 握手阶段
    C->>S: initialize (协议版本/能力协商)
    S-->>C: serverInfo + capabilities
    C->>S: notifications/initialized

    Note over C,S: 工具调用
    C->>S: tools/list
    S-->>C: tools[] (JSON Schema)
    C->>S: tools/call {name, arguments}
    S-->>C: {content: [...]}

    Note over C,S: Resources / Prompts（本章扩展）
    C->>S: resources/list
    S-->>C: resources[]
    C->>S: resources/read {uri}
    S-->>C: {contents: [...]}
    C->>S: prompts/list
    S-->>C: prompts[]
    C->>S: prompts/get {name, arguments}
    S-->>C: {messages: [...]}
```

**JSON-RPC 2.0 响应三态**：

| 字段组合 | 含义 |
|---|---|
| `{"result": ...}` | 成功响应 |
| `{"error": {"code": -32xxx, "message": "..."}}` | 错误响应 |
| `id` 为 `null` | 通知类消息，不需要响应 |

**id 类型允许 number / string / null**——这是 P0-2 修复的源头：早期版本强转 long，客户端用字符串 id 时收不到关联响应。

> ⚠️ **但官方 SDK 的 Tools 分支是例外**：`McpServer.handle()` 内部的 `extractId()` 只在
> `idNode.isNumber()` 时返回值，字符串 id 会让它直接返回 `null`——HTTP 层表现为
> **200 OK + 空响应体**。本章自实现的 Resources / Prompts 分支没有这个限制。
> 详见 19.9.1 的提示和坑点 16。

---

## 19.4 引入依赖

`smart-doc-assistant/pom.xml`：

```xml
<!--
  Ch19 MCP 深度开发：服务端 SDK（社区模块）。
  仅含 stdio 传输（StdioMcpServerTransport）+ 协议层 + Jackson；
  传递依赖 langchain4j-mcp 已由 Ch17 引入，本章不再额外加。
  HTTP 传输由本章自实现（基于 Spring MVC + McpServer.handle(JsonNode)）。
  ⚠️ StdioMcpServerTransport 把 JSON-RPC 协议写 System.out；
     日志必须走 System.err，否则污染协议流导致客户端断开。
-->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-mcp-server</artifactId>
    <version>${langchain4j.beta.version}</version>
</dependency>
```

**版本对齐**：`${langchain4j.beta.version}` = `1.22.0-beta32`，与 `langchain4j-mcp`（同一条 beta 线）保持一致。注意别把它和核心的 `1.22.0` 混成一个属性——稳定模块和实验模块是两条发布线，混用会 404。核心的 `1.22.0` 由 pom 顶部的 `<dependencyManagement>` 钉住（见 Ch01），社区模块不在其中，所以这里必须显式写版本。

---

## 19.5 实战一：stdio MCP Server（Claude Desktop 方式）

stdio 模式是 Claude Desktop / Cursor / Gemini CLI 等本地 MCP 客户端的**首选方式**：客户端启动一个 Java 子进程，通过 stdin/stdout 走 JSON-RPC。本章先做这个。

### 19.5.1 KnowledgeBaseMcpTools：把 `@Tool` 暴露给外部

这是连接"业务能力"与"MCP 协议"的桥梁。`com/example/assistant/mcp/KnowledgeBaseMcpTools.java`：

```java
@Slf4j
public class KnowledgeBaseMcpTools {

    /**
     * 工具白名单的单一真相源。{@link McpSecurityService} 直接引用此列表，
     * 加新工具时只需在这里加方法 + 在此列表里加名字，不会有两个真相源漂移的风险。
     */
    public static final List<String> TOOL_NAMES = List.of(
            "searchKnowledgeBase", "askWithRag", "getKnowledgeBaseStats"
    );

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RetrievalAugmentor retrievalAugmentor;

    public KnowledgeBaseMcpTools(EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> embeddingStore,
                                RetrievalAugmentor retrievalAugmentor) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.retrievalAugmentor = retrievalAugmentor;
    }

    @Tool("查询本地知识库，返回与问题最相关的文档片段（语义检索）。用于回答基于文档的事实性问题。")
    public String searchKnowledgeBase(
            @P("用户的自然语言问题，例如：'七天无理由退货的条件是什么？'") String question,
            @P("返回片段数量，1 到 10 之间；常用 3 或 5") Integer topK,
            @P("最低相似度阈值，0 到 1 之间；0 表示不过滤，0.5~0.7 是常用起点") Double minScore) {

        String q = requireQuestion(question);
        // ... 复用 Ch08 的 DocumentSearchService.search() 相同逻辑 ...
        int k = (topK == null || topK < 1) ? 3 : Math.min(topK, 10);
        double threshold = (minScore == null) ? 0.0 : Math.max(0.0, Math.min(1.0, minScore));
        Embedding queryEmbedding = embeddingModel.embed(q).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding).maxResults(k).minScore(threshold).build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();
        // ... 格式化返回 ...
    }

    @Tool("高级 RAG 检索：复用父子分块 + 向量/关键词混合 + reranker，返回精排后的文档片段。比纯向量检索质量更高，但更慢。")
    public String askWithRag(
            @P("用户的自然语言问题") String question,
            @P("返回片段数量，常用 3 或 5") Integer topK,
            @P("最低相似度阈值，0 到 1 之间；默认 0 不过滤") Double minScore) {
        // ... 复用 Ch12 的 RetrievalAugmentor.augment() 相同逻辑 ...
    }

    @Tool("查询知识库统计信息：embedding 向量维度、当前 embedding 模型与向量库实现。")
    public String getKnowledgeBaseStats() {
        return String.format("知识库统计：\n- Embedding 维度: %d\n- Embedding 模型: %s\n- Vector Store: %s",
                embeddingModel.dimension(),
                embeddingModel.getClass().getSimpleName(),
                embeddingStore.getClass().getSimpleName());
    }

    /** P2-5 修复：question null/blank 校验，避免 embeddingModel.embed(null) 抛 NPE */
    private String requireQuestion(String question) {
        if (question == null || question.isBlank()) {
            log.warn("⚠️ [MCP] tool called with empty/null question");
            return "";
        }
        return question;
    }
}
```

**关键设计点**：

1. **复用 Ch08/Ch12 的 Bean**：`EmbeddingModel` / `EmbeddingStore` / `RetrievalAugmentor` 三个 Bean 在主应用里已有，stdio 启动器直接 `getBean()` 取出，零代码重复
2. **TOOL_NAMES 单一真相源**：方法名 `@Tool("...")` 是工具的"对外身份"，`TOOL_NAMES` 列表是"允许谁调用"，两处必须同步——用静态常量固化
3. **不加 `@Component`**：stdio 启动器是独立进程，由 `McpServerStarter` 显式 `new`，避免与 Spring 上下文里的同名 Bean 冲突
4. **参数校验前置**：避免 `embed(null)` NPE 让整个工具调用链路断裂

### 19.5.2 McpServerStarter：stdin/stdout 入口

`com/example/assistant/mcp/McpServerStarter.java` 核心：

```java
public final class McpServerStarter {

    public static void main(String[] args) throws Exception {
        // P0-5 修复：把 Logback 输出重定向到 stderr，stdout 只剩 JSON-RPC 协议流
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
        redirectLogbackToStderr();

        // 精准加载 Spring 上下文（不拉 Tomcat/Actuator/HTTP 客户端）
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        StdioMcpServerTransport transport = null;
        try {
            ctx.register(AppConfig.class);
            // 子进程不会像 SpringApplication 那样自动读 application.yml，
            // 手动灌进 Environment，否则 @Value 全部落到注解默认值
            loadClasspathYaml(ctx);
            ctx.refresh();

            EmbeddingModel embeddingModel = ctx.getBean(EmbeddingModel.class);
            EmbeddingStore<TextSegment> embeddingStore = ctx.getBean(EmbeddingStore.class);
            RetrievalAugmentor retrievalAugmentor = ctx.getBean(RetrievalAugmentor.class);

            // P2-4 修复：通过工厂方法复用，避免与 McpServerConfig 重复装配代码
            McpServer server = McpServerFactory.create(embeddingModel, embeddingStore, retrievalAugmentor);
            transport = new StdioMcpServerTransport(server);

            // 阻塞直到客户端断开
            transport.awaitClose();
        } finally {
            // P0-4 修复：显式按反顺序释放——先 transport.close() 再 ctx.close()，
            // 避免 try-with-resources 在异常路径下漏掉释放
            if (transport != null) transport.close();
            ctx.close();
        }
    }
}
```

**关键约束：日志必须走 stderr**

```java
private static void redirectLogbackToStderr() {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    loggerContext.reset();   // 清掉默认 stdout appender

    ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
    appender.setContext(loggerContext);
    appender.setTarget("System.err");   // ← 关键
    // ... PatternLayoutEncoder 配置 ...
    appender.start();
    loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
}
```

**为什么必须这样**：stdio 传输下，`StdioMcpServerTransport` 把 JSON-RPC 消息写到 `System.out`。任何 SLF4J / Logback / Jackson 默认日志输出到 stdout 会污染协议流——客户端解析失败立即断开。

> ✅ **最佳实践**：stdio 启动器里**只允许 `System.err.println(...)`** 与 SLF4J（已重定向）写日志，其他所有日志框架都得关掉。Spring Boot 自动 Logback 配置会被 `org.springframework.boot.logging.LoggingSystem=none` 关闭，然后我们编程式接管。

### 19.5.3 客户端配置（Claude Desktop 示例）

`~/Library/Application Support/Claude/claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "smart-doc-assistant": {
      "command": "java",
      "args": ["-jar", "/path/to/smart-doc-assistant-0.16.0-SNAPSHOT-mcp.jar"],
      "env": {
        "DEEPSEEK_API_KEY": "sk-xxx",
        "DASHSCOPE_API_KEY": "sk-xxx"
      }
    }
  }
}
```

**注意这里必须是 `-jar`，不能写成 `-cp xxx.jar com.example.assistant.mcp.McpServerStarter`**。原因见 19.9.2：`mvn package` 出来的是 Spring Boot fat jar，类在 `BOOT-INF/classes/`、依赖在 `BOOT-INF/lib/`，`-cp` 根本看不见它们，启动会直接 `ClassNotFoundException`。

---

## 19.6 实战二：Streamable HTTP 传输（远程调用方式）

stdio 适合本地子进程，生产环境通常需要**远程 MCP Server**——客户端通过 HTTP 调用。本章用 Spring MVC 自实现 HTTP 传输（官方 SDK 没提供）。

### 19.6.1 McpServerConfig：Spring Bean 装配

```java
@Configuration
public class McpServerConfig {

    /**
     * 装配 MCP Server Bean（含 {@link KnowledgeBaseMcpTools#TOOL_NAMES} 三个 @Tool 方法）。
     * 与 stdio 启动器共用 McpServerFactory.create()，保证双传输行为完全一致。
     */
    @Bean
    public McpServer mcpServer(EmbeddingModel embeddingModel,
                               EmbeddingStore<TextSegment> embeddingStore,
                               RetrievalAugmentor retrievalAugmentor) {
        return McpServerFactory.create(embeddingModel, embeddingStore, retrievalAugmentor);
    }

    @Bean public McpResourcesProvider mcpResourcesProvider() { return new McpResourcesProvider(); }
    @Bean public McpPromptsProvider mcpPromptsProvider() { return new McpPromptsProvider(); }

    @Bean
    public McpJsonRpcDispatcher mcpJsonRpcDispatcher(McpServer mcpServer,
                                                     McpResourcesProvider resources,
                                                     McpPromptsProvider prompts) {
        return new McpJsonRpcDispatcher(mcpServer, resources, prompts);
    }
}
```

### 19.6.2 McpServerFactory：消除重复装配

```java
public final class McpServerFactory {
    public static final String SERVER_NAME = "smart-doc-assistant";
    public static final String SERVER_VERSION = "0.17.0";

    /** stdio 启动器与 Spring HTTP 模式共用此工厂 */
    public static McpServer create(EmbeddingModel embeddingModel,
                                   EmbeddingStore<TextSegment> embeddingStore,
                                   RetrievalAugmentor retrievalAugmentor) {
        KnowledgeBaseMcpTools tools = new KnowledgeBaseMcpTools(embeddingModel, embeddingStore, retrievalAugmentor);

        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(SERVER_NAME);
        serverInfo.setVersion(SERVER_VERSION);

        return new McpServer(List.of(tools), serverInfo);
    }
}
```

### 19.6.3 McpHttpController：POST /mcp 端点

```java
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpHttpController {

    private final McpJsonRpcDispatcher dispatcher;
    private final McpAuditService auditService;
    private final McpSecurityService securityService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(@RequestBody JsonNode request,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                          @RequestHeader(value = "X-MCP-API-Key", required = false) String apiKeyHeader) {
        long start = System.currentTimeMillis();
        String method = request.path("method").asText("");
        // P0-2 修复：透传 id 节点（保留 number/string/null 类型）
        JsonNode rawId = request.get("id");
        Object id = (rawId == null || rawId.isNull()) ? null : rawId;

        // 鉴权：initialize/ping 是握手阶段免鉴权
        if (!method.equals("initialize") && !method.equals("ping") && !method.equals("notifications/initialized")) {
            if (!securityService.isAuthorized(authHeader, apiKeyHeader)) {
                return ResponseEntity.status(401)
                        .body(JsonRpcResponse.error(id, -32001, "Unauthorized"));
            }
            // 工具白名单检查
            if (method.equals("tools/call")) {
                String toolName = request.path("params").path("name").asText("");
                if (!securityService.isToolAllowed(toolName)) {
                    return ResponseEntity.status(403)
                            .body(JsonRpcResponse.error(id, -32601, "Tool not allowed: " + toolName));
                }
            }
        }

        Object response;
        try { response = dispatcher.handle(request); }
        catch (Exception e) { response = JsonRpcResponse.error(id, -32603, e.getMessage()); }

        // 审计 + 耗时
        if (method.equals("tools/call")) {
            String toolName = request.path("params").path("name").asText("");
            auditService.record(toolName, request.path("params").path("arguments").toString(),
                    System.currentTimeMillis() - start, false);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/audit")
    public Map<String, Object> recentAudit(/*鉴权头*/) {
        return Map.of("entries", auditService.recent(20), "count", 20);
    }
}
```

**协议要点**：返回值类型为 `Object`——Tools 分支返回 SDK 的 `McpJsonRpcMessage`（SDK 内部用 `@JsonAnyGetter` 序列化），Resources/Prompts 分支返回本章的 `JsonRpcResponse`（显式字段），Jackson 自动按各自字段序列化。

---

## 19.7 扩展 Resources / Prompts 原语（官方 SDK 不支持 → 自实现）

官方 SDK 1.22.0-beta32 **只支持 Tools**。但 MCP 规范定义了 Tools / Resources / Prompts 三种。本章用**装饰器模式**包装 `McpServer`，把 Resources 和 Prompts 也接上：

```mermaid
flowchart LR
    REQ[JSON-RPC Request] --> DISP[McpJsonRpcDispatcher<br/>装饰器]
    DISP -->|"method=tools/*<br/>initialize, ping"| MCPS[McpServer.handle<br/>官方 SDK]
    DISP -->|"method=resources/list"| RP[McpResourcesProvider]
    DISP -->|"method=resources/read"| RP
    DISP -->|"method=prompts/list"| PP[McpPromptsProvider]
    DISP -->|"method=prompts/get"| PP
    DISP -->|"其他"| ERR[返回 Method Not Found<br/>-32601]
```

### 19.7.1 McpResourcesProvider：扫描 classpath:docs/

```java
@Slf4j
public class McpResourcesProvider {

    private static final String DOCS_PATH = "docs/";
    private static final String URI_SCHEME = "docs://";

    private final Map<String, ResourceEntry> resources = new LinkedHashMap<>();

    public McpResourcesProvider() { loadAll(); }

    private void loadAll() {
        // 启动时扫描 classpath:docs/，全部读进内存
        for (String name : List.of("product-faq.txt", "refund-policy.txt",
                                   "technical-guide.md", "report.pdf")) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(DOCS_PATH + name)) {
                byte[] bytes = in.readAllBytes();
                resources.put(URI_SCHEME + name,
                              new ResourceEntry(URI_SCHEME + name, name, detectMime(name), bytes));
            } catch (IOException e) { /* log */ }
        }
    }

    public List<Map<String, Object>> listResources() { /* 返回 {uri, name, mimeType}[] */ }

    public Optional<List<Map<String, Object>>> readResource(String uri) {
        // P2-3 修复：二进制用 blob (base64)，文本用 text 字段——符合 MCP 规范
        ResourceEntry entry = resources.get(uri);
        if (entry == null) return Optional.empty();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", entry.uri);
        content.put("mimeType", entry.mime);
        if (entry.mime.startsWith("text/") || entry.mime.startsWith("application/json")) {
            content.put("text", new String(entry.bytes, StandardCharsets.UTF_8));
        } else {
            content.put("blob", Base64.getEncoder().encodeToString(entry.bytes));
        }
        return Optional.of(List.of(content));
    }

    private static String detectMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        return "text/plain; charset=utf-8";
    }
}
```

**P2-3 修复重点**：PDF 等二进制资源**不能再当文本读**（之前的实现把 PDF bytes 解码成 UTF-8 乱码存进 `text` 字段，误导客户端）。MCP 规范定义了 `blob` 字段用于二进制，本章按规范实现：客户端按 `mimeType` 决定解析方式。

> ⚠️ **教学简化**：硬编码 4 个文件名清单（与 Ch10 的 `DocumentIngestionService` 同样硬编码）。生产建议抽 `DocsScanner` 工具类用 ClassGraph 自动扫描，避免**两个真相源漂移**（P1-1 待办）。

### 19.7.2 McpPromptsProvider：3 个模板 + `{{var}}` 替换

```java
public class McpPromptsProvider {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    public McpPromptsProvider() {
        registerCodeReview();   // review-code
        registerSummarizeDoc();  // summarize-doc
        registerQaPolicy();      // qa-policy-check
    }

    /** 对应 MCP prompts/get：把 args 里的值替换进模板的 {{var}} */
    public Optional<List<Map<String, Object>>> getPrompt(String name, Map<String, Object> args) {
        PromptDefinition def = prompts.get(name);
        if (def == null) return Optional.empty();
        String rendered = renderTemplate(def.template, args);
        return Optional.of(List.of(Map.of(
            "role", "user",
            "content", Map.of("type", "text", "text", rendered)
        )));
    }

    /** P1-3 修复：真正的模板渲染（之前接 args 但不用） */
    private static String renderTemplate(String template, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = safeArgs.get(m.group(1));
            // 缺值时替换为空字符串，避免 LLM 看到原始 {{var}} 被当成指令
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

**3 个预定义模板**：
- `review-code`：代码审查清单（language / code / focus）
- `summarize-doc`：文档摘要（document / max_words / language）
- `qa-policy-check`：强制"基于资料回答"的政策问答模板

**P1-3 修复**：之前的实现接 `args` 参数但**完全不用**——这是 API 假象，误导调用方。本次真正实现 `{{var}}` 正则替换。生产建议替换为 StringTemplate / Pebble 等正式模板引擎（支持 `{{x | default(...)}}` 过滤器、`{% if %}` 控制结构）。

### 19.7.3 McpJsonRpcDispatcher：装饰器模式分发

```java
@Slf4j
public class McpJsonRpcDispatcher {
    private final McpServer mcpServer;
    private final McpResourcesProvider resourcesProvider;
    private final McpPromptsProvider promptsProvider;

    public Object handle(JsonNode request) {
        // P0-2 修复：透传 id 节点，保留 number/string/null 类型
        JsonNode idNode = request.get("id");
        Object id = (idNode == null || idNode.isNull()) ? null : idNode;

        String method = request.path("method").asText("");
        switch (method) {
            case "resources/list":
                return JsonRpcResponse.success(id, Map.of("resources", resourcesProvider.listResources()));
            case "resources/read": /* ... */
            case "prompts/list":
                return JsonRpcResponse.success(id, Map.of("prompts", promptsProvider.listPrompts()));
            case "prompts/get": /* P1-4 修复：args 保留原始 JSON 类型 */
                Map<String, Object> args = parseArgs(request.path("params").path("arguments"));
                /* ... */
            default:
                return mcpServer.handle(request);   // 委托 SDK：tools/*、initialize、ping
        }
    }

    /** P1-4 修复：保留 number/boolean/string/object/array，不再 asText() 强转 */
    private static Map<String, Object> parseArgs(JsonNode argsNode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (argsNode == null || !argsNode.isObject()) return out;
        argsNode.fields().forEachRemaining(e -> out.put(e.getKey(), toJavaValue(e.getValue())));
        return out;
    }
}
```

**为什么用 Object 而不是 long 作为 id**：JSON-RPC 2.0 允许 `id` 是 number / string / null。早期版本用 `request.path("id").asLong(0)` 强转——客户端用 `"req-001"` 字符串 id 发请求，**响应 id 变 0**，客户端无法关联请求-响应。这是协议级 bug。

### 19.7.4 JsonRpcResponse：自定义 JSON-RPC 2.0 结构

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {
    public final String jsonrpc = "2.0";
    public final Object id;          // ← 保留任意类型
    public final Map<String, Object> result;   // null = 错误响应
    public final ErrorBody error;              // null = 正常响应

    public static JsonRpcResponse success(Object id, Map<String, Object> result) { ... }
    public static JsonRpcResponse error(Object id, int code, String message) { ... }

    public static class ErrorBody {
        public final int code;
        public final String message;
    }
}
```

**为什么自造**：官方 SDK 的 `McpJsonRpcMessage` 是轻量 POJO，只带 `jsonrpc` + `id`；真正的 `result`/`error` 字段依赖 Jackson `@JsonAnyGetter` 魔法——不利于扩展 Resources/Prompts。本章做更朴素的事：显式字段 + 显式构造，更易读、易测、易扩展。

---

## 19.8 安全与审计（呼应 Ch16）

### 19.8.1 McpSecurityService：API Key + 工具白名单

```java
@Slf4j
@Service
public class McpSecurityService {

    private static final int MIN_API_KEY_LENGTH = 16;

    private final String apiKey;
    private final List<String> allowedTools;

    public McpSecurityService(@Value("${chat.mcp.api-key:dev-mcp-key-replace-in-production}") String apiKey) {
        this.apiKey = apiKey;
        // P1-2 修复：单一真相源——从 KnowledgeBaseMcpTools.TOOL_NAMES 直接读取
        this.allowedTools = KnowledgeBaseMcpTools.TOOL_NAMES;
    }

    /** P0-3 修复：启动期校验，禁止空/默认值/过短 Key 上线 */
    @PostConstruct
    void validateConfig() {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalStateException("Invalid chat.mcp.api-key: must be at least "
                    + MIN_API_KEY_LENGTH + " characters (current length=" + (apiKey == null ? 0 : apiKey.length()) + ")");
        }
        if ("dev-mcp-key-replace-in-production".equals(apiKey)) {
            log.warn("⚠️ [MCP Security] Using default API key — change chat.mcp.api-key for production!");
        }
    }

    public boolean isAuthorized(String authHeader, String customHeader) {
        // P2-2 修复：Bearer 大小写不敏感
        if (authHeader != null && authHeader.trim().length() > 7) {
            String prefix = authHeader.trim().substring(0, 7);
            if (prefix.equalsIgnoreCase("Bearer ")) {
                return constantTimeEquals(authHeader.trim().substring(7).trim(), apiKey);
            }
        }
        return customHeader != null && constantTimeEquals(customHeader.trim(), apiKey);
    }

    /** 常量时间比较，防时序攻击 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
```

**P0-3 修复重点**：原实现 `apiKey=null` 或 `apiKey=""` 时 `constantTimeEquals("", "")` 返回 true——**空 Key 当万能 Key**。启动期直接抛异常阻断，比运行时拒绝更安全（运维事故无法"带病上线"）。

### 19.8.2 McpAuditService：调用审计 + 敏感字段 redact

```java
@Slf4j
@Service
public class McpAuditService {

    private static final int MAX_ENTRIES = 500;
    private static final String[] SENSITIVE_KEYS = {
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "auth", "credential", "private", "session"
    };

    // P0-6 修复：环形 buffer 容量上限（避免长时间运行内存泄漏）
    private final AuditEntry[] ringBuffer = new AuditEntry[MAX_ENTRIES];
    private int writeIndex = 0;
    private int size = 0;

    public synchronized void record(String toolName, String arguments, long durationMs, boolean failed) {
        // P1-5 修复：写入前 redact 敏感字段
        String safeArgs = redact(arguments);
        AuditEntry e = new AuditEntry(System.currentTimeMillis(), toolName, safeArgs, durationMs, failed);
        ringBuffer[writeIndex] = e;
        writeIndex = (writeIndex + 1) % MAX_ENTRIES;
        if (size < MAX_ENTRIES) size++;
    }

    public synchronized List<AuditEntry> recent(int n) {
        int take = Math.min(n, size);
        List<AuditEntry> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            int idx = (writeIndex - 1 - i + MAX_ENTRIES) % MAX_ENTRIES;
            out.add(ringBuffer[idx]);
        }
        return out;
    }

    /** P1-5 修复：把 "password":"123456" → "password":"***" */
    static String redact(String args) {
        if (args == null || args.isEmpty()) return args;
        String out = args;
        for (String key : SENSITIVE_KEYS) {
            out = out.replaceAll("(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3");
        }
        return out;
    }

    public record AuditEntry(long timestamp, String toolName, String arguments, long durationMs, boolean failed) {}
}
```

**P0-6 + P1-5 修复**：
- 环形 buffer 容量 500 条（教学版内存），超过覆盖最旧
- audit 日志 redact 常见敏感字段，避免用户问"我的密码是123456"被原样写入日志

> ⚠️ **生产化**：`McpAuditService` 仅做内存版演示。生产应写 ELK / 数据库，且 redact 应改用 Jackson 树遍历（更可靠）。

---

## 19.9 端点验证

### 19.9.1 Streamable HTTP 模式

> ⚠️ **先看这条**：JSON-RPC 的 `id` **必须是数字**。官方社区 SDK 的 `McpServer.handle()` 里
> `extractId()` 只认 `idNode.isNumber()`——传字符串 id 会让 `handle()` 直接返回 `null`，
> HTTP 层表现为 **200 OK 但响应体长度为 0**（不是 4xx，是静默丢弃）。自实现的
> Resources / Prompts 分支不受影响，因为 `JsonRpcResponse.id` 是 `Object`。
> 下面的命令全部用数字 id。

```bash
# 1. 列出 MCP Server 暴露的工具（id 用数字 1）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```
```json
{"id":1,"result":{"tools":[{"name":"searchKnowledgeBase","description":"查询本地知识库，返回与问题最相关的文档片段（语义检索）。用于回答基于文档的事实性问题。","inputSchema":{"type":"object","properties":{"question":{"type":"string","description":"用户的自然语言问题，例如：'七天无理由退货的条件是什么？'"},"topK":{"type":"integer","description":"返回片段数量，1 到 10 之间；常用 3 或 5"},"minScore":{"type":"number","description":"最低相似度阈值，0 到 1 之间；0 表示不过滤，0.5~0.7 是常用起点"}},"required":["question","topK","minScore"]}},...]}}
```

```bash
# 2. 调用工具（参数名是 question，不是 query —— 写错会拿到业务错误而非参数错误）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"searchKnowledgeBase","arguments":{"question":"七天无理由退货的条件是什么？","topK":3,"minScore":0.5}}}'
```

> 💡 **参数名写错时的返回长这样**——不是 JSON-RPC 的 `-32602 Invalid params`，
> 而是工具方法体里的业务校验抛出的异常被 SDK 包成 `isError`：
> ```json
> {"id":2,"result":{"content":[{"text":"text cannot be null or blank","type":"text"}],"isError":true},"jsonrpc":"2.0"}
> ```
> 看到这个 `isError:true` 就知道是**参数名/取值不对**，不是服务端挂了。

```bash
# 3. Resources 原语（4 个 classpath:docs/ 下的文件）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/list","params":{}}'
```
```json
{"id":3,"result":{"resources":[{"uri":"docs://product-faq.txt","name":"product-faq.txt","mimeType":"text/plain; charset=utf-8"},{"uri":"docs://refund-policy.txt","name":"refund-policy.txt","mimeType":"text/plain; charset=utf-8"},{"uri":"docs://technical-guide.md","name":"technical-guide.md","mimeType":"text/markdown"},{"uri":"docs://report.pdf","name":"report.pdf","mimeType":"application/pdf"}]},"jsonrpc":"2.0"}
```

```bash
# 4. Resources 读取（text 格式）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"docs://refund-policy.txt"}}'
# → {"id":4,"result":{"contents":[{"uri":"docs://refund-policy.txt","mimeType":"text/plain; charset=utf-8","text":"退款政策说明（客服处理依据）..."}]}}

# 5. Resources 读取（blob 格式，PDF 走 base64）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"docs://report.pdf"}}'
# → {"id":6,"result":{"contents":[{"uri":"docs://report.pdf","mimeType":"application/pdf","blob":"JVBERi0xLjQKJZOMi54gUmVwb3J0TGFi..."}]}}
```

```bash
# 6. Prompts 原语 + {{var}} 替换（3 个模板：review-code / summarize-doc / qa-policy-check）
curl -s -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":7,"method":"prompts/get","params":{"name":"qa-policy-check","arguments":{"question":"运费谁出","context":"用户买的机器坏了"}}}'
```
```json
{"id":7,"result":{"messages":[{"role":"user","content":{"text":"你是智能客服助手。严格依据下方【参考资料】回答用户问题。\n规则：\n- 资料未提及的内容，明确说\"资料中未提及\"，绝对不要编造\n- 引用资料时标注片段编号\n- 结尾提示\"如需进一步帮助请联系人工客服\"\n\n【参考资料】\n用户买的机器坏了\n\n【用户问题】\n运费谁出\n\n【回答】\n","type":"text"}}]},"jsonrpc":"2.0"}
```
> `{{question}}` 和 `{{context}}` 都被替换成了实参，没有残留的原始占位符——这是 P1-3 修复的验证点。

```bash
# 7. 工具白名单验证（不在 TOOL_NAMES 里的工具直接 403）
curl -s -w '\nstatus:%{http_code}\n' -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production" \
  -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"rm_rf","arguments":{}}}'
```
```json
{"id":8,"error":{"code":-32601,"message":"Tool not allowed: rm_rf (allowed: [searchKnowledgeBase, askWithRag, getKnowledgeBaseStats])"},"jsonrpc":"2.0"}
status:403
```

```bash
# 8. 审计查询（只记录 tools/call）
curl -s "http://localhost:8090/mcp/audit?n=10" \
  -H "Authorization: Bearer dev-mcp-key-replace-in-production"
```
```json
{"entries":[{"timestamp":1793196807289,"toolName":"searchKnowledgeBase","arguments":"{\"query\":\"退款政策\"}","durationMs":5,"failed":false}],"count":10}
```

### 19.9.2 stdio 模式（Claude Desktop）

```bash
# 1. 单独打一个 MCP 入口的可执行 jar（普通 jar 里的 Main-Class 是 Spring 的 JarLauncher，
#    直接 java -jar 会拉起整个 Web 应用，而不是 MCP 子进程）
cd smart-doc-assistant
mvn clean package -DskipTests -Pmcp
ls target/smart-doc-assistant-0.16.0-SNAPSHOT-mcp.jar

# 2. 配置 Claude Desktop（19.5.3 里的 args 指向这个 -mcp.jar）

# 3. 启动 Claude Desktop → 在对话里问：
"查询知识库里关于七天无理由退货的内容"
# Claude Desktop 自动启动 McpServerStarter，调用 searchKnowledgeBase 工具
```

`-Pmcp` 是 pom 里的一个 profile：它让 `spring-boot-maven-plugin` 用 `classifier=mcp` + `mainClass=com.example.assistant.mcp.McpServerStarter` 再压一个包。你可以先用 MCP 的 `initialize` 请求自测，不用真开 Claude Desktop：

```bash
# ⚠️ 别写成 `printf ... | java -jar ...` 一把梭：三条消息会随管道瞬间 EOF，
#    进程在响应写回之前就收尾退出，stdout 一行都看不到（我们实测如此）。
#    真实客户端不会关管道，所以自测时也要让喂消息的循环撑住。
{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"0.0.1"}}}'
  sleep 2
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  sleep 1
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 3
} | java -jar target/smart-doc-assistant-0.16.0-SNAPSHOT-mcp.jar
```

stdout 应该只有两行 JSON-RPC 响应（`initialize` 与 `tools/list`），日志全部走 stderr。**如果 stdout 里混进任何一行非 JSON 文本，客户端就会解析失败。**

---

## 19.10 ⚠️ P0 工程修复记录

本章做了 6 项 P0 修复，全是**实战中会爆炸的雷**：

| ID | 问题 | 症状 | 修复 |
|---|---|---|---|
| **P0-1** | McpHttpController 双 "not not" typo | 审计日志看不清 | 改为 "not in" |
| **P0-2** | JSON-RPC id 强转 long | 客户端用字符串 id 时收不到关联响应 | `JsonRpcResponse.id` 改 `Object`；Dispatcher / Controller 透传 JsonNode（⚠️ 自实现的 Resources/Prompts 分支支持字符串 id，但**官方 SDK 的 Tools 分支仍然只认数字 id**，见坑点 16） |
| **P0-3** | 空 API Key 万能 Key漏洞 | `constantTimeEquals("", "")` 返回 true → 任意 Key 通过鉴权 | `@PostConstruct` 校验 ≥16 字符，违者启动失败 |
| **P0-4** | try-with-resources 资源顺序 | awaitClose() 抛异常时 ctx.close() 漏执行 | 显式 try-finally，反顺序释放 |
| **P0-5** | stdio 启动器日志污染 stdout | Spring Boot 默认日志写 stdout → 协议流污染 → 客户端断开 | 编程式重配 Logback：stderr only |
| **P0-6** | 审计无上限 | 长时间运行 entries 无限增长 → 内存泄漏 | 环形 buffer MAX_ENTRIES=500，覆盖最旧 |

**经验**：
- **不要相信"看起来对"的代码**——typo、空值、字符串比较等小问题会被运行时放大
- **官方 SDK 的设计取舍要清楚**——`McpJsonRpcMessage` 用 `@JsonAnyGetter` 是因为 SDK 想"基础类型简洁 + 字段灵活"，但自定义协议（Resources/Prompts）需要显式字段结构
- **stdio 传输是隐藏陷阱的雷区**——进程生命周期、日志流、子进程退出码、信号处理都要小心

---

## 19.11 ⚠️ 高频坑点清单

| # | 坑 | 现象 | 对策 |
|---|---|---|---|
| 1 | **stdio 日志污染 stdout** | 客户端解析失败立即断开 | `redirectLogbackToStderr()` 编程式接管；`org.springframework.boot.logging.LoggingSystem=none` |
| 2 | **JSON-RPC id 类型丢失** | 客户端用字符串 id 时收不到响应 | id 用 `Object` 承载，透传 JsonNode；⚠️ 官方 SDK 的 Tools 分支仍只认数字 id（坑点 16） |
| 3 | **空 / 默认 API Key 上线** | 任意 Key 通过鉴权 | `@PostConstruct` 启动期校验 |
| 4 | **工具白名单两个真相源漂移** | 加新工具忘了同步白名单 | `KnowledgeBaseMcpTools.TOOL_NAMES` 单一来源 |
| 5 | **PDF 当文本读存乱码** | clients 拿到 base64 乱码 | MCP 规范的 `blob` 字段（二进制）+ `text` 字段（文本） |
| 6 | **审计日志泄露敏感数据** | 用户问"密码是 123456"被原样写入 | `redact()` 正则过滤 password/token/apiKey 等关键字 |
| 7 | **args 类型丢失（数字/布尔变字符串）** | 客户端传 `false: true` 被当成 "true" 文本 | `parseArgs()` 保留原始 JSON 类型（number/bool/object/array） |
| 8 | **McpResourcesProvider 硬编码文件名** | 新增文档要改两个地方 | 待办：抽 `DocsScanner` 工具类统一扫描（**P1-1**） |
| 9 | **Prompts `{{var}}` 不替换** | LLM 看到原始 `{{language}}` 当成指令 | `renderTemplate()` 正则替换；缺值时换空字符串 |
| 10 | **Stdio 子进程泄漏** | Claude 退出但 Java 进程残留 | Claude Desktop 负责管理子进程生命周期；自实现客户端要 `transport.close()` + `mcpClient.close()` |
| 11 | **Bearer 大小写敏感** | `bearer xxx` 鉴权失败 | `equalsIgnoreCase("Bearer ")` |
| 12 | **McpServerStarter 启动 Spring 日志污染** | 启动 banner / Bean 装配日志写到 stdout | `LoggingSystem=none` + 编程式接管 Logback |
| 13 | **用 `-cp` 跑 fat jar 里的入口类** | `ClassNotFoundException: com.example.assistant.mcp.McpServerStarter` | fat jar 的类在 `BOOT-INF/classes/`，`-cp` 看不到；用 `-Pmcp` 打出独立可执行 jar，`java -jar` 启动 |
| 14 | **commons-logging 与 spring-jcl 并存** | stdout 多出一行 `Standard Commons Logging discovery in action with spring-jcl...`，客户端解析失败 | 排除 `commons-logging:commons-logging`（spring-jcl 提供同一套 API）。本例来自 pdfbox 的传递依赖 |
| 15 | **子进程读不到 `application.yml`** | 用 `AnnotationConfigApplicationContext` 时 `@Value` 全部落到注解默认值，和配置文件不一致（例如 reranker 变成 dashscope 却缺 key，启动即失败） | 启动器里手动 `YamlPropertySourceLoader` 加载 `application.yml` 进 Environment |
| 16 | **Tools 分支的 id 必须用数字** | 传字符串 id → **HTTP 200 但响应体长度 0**（不是 4xx，静默丢弃，客户端以为服务端无响应） | 官方 SDK 的 `extractId()` 只认 `idNode.isNumber()`；Tools 调用一律用数字 id。资源/提示原语是自实现分支，字符串 id 也能正常返回 |
| 17 | **工具参数名写错报的是业务错误** | 传 `query` 而工具签名是 `question` → 返回 `isError:true` + `"text cannot be null or blank"`，不是 `-32602 Invalid params` | 看 `isError` 字段区分"参数写错"和"服务端故障"；JSON Schema 里 `required` 列出的字段名就是准确参数名 |

---

## 19.12 本章小结

### 概念速查表

| 概念 | 一句话 |
|---|---|
| **MCP Server SDK** | 官方在 `langchain4j-community-mcp-server`，不在核心模块（1.22.0-beta32 实测） |
| **stdio 传输** | 子进程方式，本地 Claude Desktop / Cursor / Gemini CLI 用；`StdioMcpServerTransport` |
| **Streamable HTTP** | 远程调用方式；本章用 Spring MVC 自实现（官方 SDK 1.22.0-beta32 未提供） |
| **`@Tool` 注解** | LangChain4j 标准注解，Server SDK 自动扫 `List<Object>` Bean 生成 JSON Schema |
| **McpJsonRpcDispatcher** | 装饰器模式包装 `McpServer`，扩展 Resources / Prompts 原语 |
| **JsonRpcResponse** | 自定义 JSON-RPC 2.0 结构；`id` 保留任意类型（number/string/null） |
| **JSON-RPC id 类型** | 规范允许 number / string / null；自实现分支（资源/提示）三种都支持，**官方 SDK 的 Tools 分支只认数字 id** |
| **stdio 日志硬约束** | 所有日志必须走 stderr，stdout 只剩 JSON-RPC 协议流 |
| **McpServerFactory** | 双传输共用的工厂方法，消除 stdio/HTTP 重复装配代码 |
| **TOOL_NAMES 真相源** | 工具白名单从 `KnowledgeBaseMcpTools.TOOL_NAMES` 读取，避免两个真相源漂移 |
| **`{{var}}` 模板渲染** | Prompts 用正则替换；缺值时换空字符串（避免 LLM 把 `{{var}}` 当指令） |
| **MCP blob 字段** | 二进制资源（PDF / 图片）走 `blob` 字段（base64）；文本走 `text` 字段（UTF-8） |

### 项目代码状态表（v0.17）

| 模块 | 文件 | 说明 |
|---|---|---|
| 依赖 | `pom.xml` | **新增**：`langchain4j-community-mcp-server:1.22.0-beta32` |
| 工具 | `mcp/KnowledgeBaseMcpTools.java` | **新增**：3 个 `@Tool` + `TOOL_NAMES` 单一真相源 + question 校验 |
| 工具 | `mcp/McpServerFactory.java` | **新增**：双传输共用工厂 |
| 启动 | `mcp/McpServerStarter.java` | **新增**：stdio 入口（main 方法）+ Logback→stderr 重定向 |
| 配置 | `mcp/McpServerConfig.java` | **新增**：Spring 上下文装配 HTTP 模式的 MCP Server |
| HTTP | `mcp/McpHttpController.java` | **新增**：POST /mcp + GET /mcp/audit + 鉴权/白名单 |
| 协议 | `mcp/JsonRpcResponse.java` | **新增**：JSON-RPC 2.0 响应结构（id 任意类型） |
| 协议 | `mcp/McpJsonRpcDispatcher.java` | **新增**：装饰器模式分发 Tools/Resources/Prompts |
| 资源 | `mcp/McpResourcesProvider.java` | **新增**：classpath:docs/ 扫描 + blob/text 双格式 |
| 提示 | `mcp/McpPromptsProvider.java` | **新增**：3 个模板 + `{{var}}` 渲染 |
| 安全 | `mcp/McpSecurityService.java` | **新增**：API Key 启动期校验 + Bearer 大小写不敏感 |
| 审计 | `mcp/McpAuditService.java` | **新增**：环形 buffer + redact |
| 文档 | `chapters/ch19-mcp-server-development.md` | **新增**：本章 |
| README | `smart-doc-assistant/README.md` | **新增**：端点速查表 |

### 下一章预告

**Ch21 Guardrails 安全护栏**——本章做了"准入层"防护（API Key + 工具白名单），但请求进来之后的内容层威胁（提示注入 / 越权工具 / 输出泄露）还没防：Ch21 在 Application 层与 AiServices 层各加一道护栏，把本章的安全清单从"谁在调"延伸到"调进来的是什么、吐出去的是什么"。

### 练习题

1. 用 `npx @modelcontextprotocol/inspector` 启动 MCP Inspector，给 stdio 启动器测一遍 tools/list + tools/call + resources/read + prompts/get，验证本章所有功能。
2. 把 `chat.mcp.api-key` 设为空字符串，启动主应用——观察启动失败抛 `IllegalStateException`（验证 P0-3 修复）。
3. 给 `McpPromptsProvider` 加一个新模板 `extract-fields`（用 {{fields}} {{text}} 占位符），用 curl 验证 `{{text}}` 被替换成传入的字符串。
4. 修改 `McpSecurityService.allowedTools`（或 `KnowledgeBaseMcpTools.TOOL_NAMES`），加一个新工具名但**不**在 `KnowledgeBaseMcpTools` 类加 @Tool 方法——重启后用 curl 调该工具，观察返回 403 Forbidden（验证白名单真相源）。
5. 思考题：为什么 stdio 启动器**不能用 Spring Boot 自动配置**？提示：思考 Spring Boot 启动时哪些组件会写 stdout/启动 HTTP 服务器/读 application.yml。
6. 思考题：Ch19 的 Resources / Prompts 是装饰器模式实现。如果官方 SDK 未来直接支持 `@McpResource` / `@McpPrompt`，本章的代码会怎样被简化？提示：考虑 `McpServer` 构造器的 `List<Object>` 参数和 `McpJsonRpcDispatcher.handle()` 的 switch 分发。

---

**下一章预告**：Ch21 Guardrails 安全护栏 → Ch22 可观测性 · 日志 · 成本控制 → Ch24 企业级项目工程化实战（多租户 SaaS）。