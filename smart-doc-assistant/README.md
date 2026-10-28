# smart-doc-assistant — 端点速查

> 本目录是 LangChain4j 教程（Ch01~Ch18）的配套可运行工程。当前版本与仓库根 `pom.xml` 一致（v0.16.0-SNAPSHOT）。
>
> 本文按"卷"分组列出全部 REST 端点，全部来自 `src/main/java/.../controller/` 真实注解，可直接 `curl` 验证。

## 启动

> 需要 **JDK 21**（本工程在 21.0.10 验证）。pom 里有 `maven-enforcer-plugin` 锁 `[21,22)`——JDK 22+ 会让 Lombok 注解处理失效，报一堆"找不到符号 log"。

```bash
cd smart-doc-assistant
export DEEPSEEK_API_KEY=sk-xxx          # 对话模型（Ch01 起）
export DASHSCOPE_API_KEY=sk-xxx         # 多模态（Ch09）/ Embedding 切换 bailian 时（Ch08）
export REDIS_HOST=localhost             # Ch05 起需要（不连也能启动上下文，仅聊天用不了持久化）
export REDIS_PORT=6379
mvn spring-boot:run                     # 默认监听 :8090
```

零成本启动（仅 Ch01~Ch08 + Ch10~Ch11 immemory 向量库）只需 `DEEPSEEK_API_KEY`。

---

## 卷一：Spring Boot 快速起步（Ch01~Ch04）

| Ch | 端点 | 方法 | 入参 | 出参 | 说明 |
|---|---|---|---|---|---|
| Ch01 | `/api/chat` | POST | `{message}` | `string` | 最简 Hello World |
| Ch02 | `/api/code-review` | POST | `{language, code}` | `string` | Few-shot 代码评审 |
| Ch02 | `/api/translate` | POST | `{text, targetLang}` | `string` | 翻译 |
| Ch03 | `/api/translate-v2` | POST | `{text, targetLang}` | `string` | 多模型 Bean 切换（creative-model） |
| Ch03 | `/api/math/solve` | POST | `{question}` | `string` | CoT 数学题 |
| Ch03 | `/api/creative` | POST | `{topic}` | `string` | 高 temperature 创意写作 |
| Ch03 | `/api/review/analyze` | POST | `{reviews[]}` | `string` | Few-shot 评论分析 |
| Ch03 | `/api/review/analyze-v3` | POST | `{reviews[]}` | `ReviewAnalysis` | 结构化输出 POJO（Ch07 落地） |
| Ch04 | `/api/chat/stream` | POST | `{sessionId, message}` | SSE | 流式对话（`-N` 接收） |
| Ch04 | `/api/creative/stream` | POST | `{topic}` | SSE | 创意流式 |
| Ch04 | `/api/chat/stream/active` | GET | — | JSON | 当前活跃流式会话数 |
| Ch04 | `/api/chat/stream/cancel` | POST | `{sessionId}` | JSON | 主动取消流式 |

示例：
```bash
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"你好"}'

curl -N -X POST http://localhost:8090/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1","message":"用 50 字介绍 Spring Boot"}'
```

---

## 卷二：核心能力深度拆解（Ch05~Ch09）

| Ch | 端点 | 方法 | 说明 |
|---|---|---|---|
| Ch05 | `/api/chat`（沿用） | POST | 多轮会话自动由 Redis ChatMemoryStore 持久化（按 `sessionId` 隔离，TTL 30 天） |
| Ch06 | `/api/tools/chat` | POST | `@Tool` 工具调用：TimeTools / CalculatorTools / OrderTools 自动注册 |
| Ch07 | `/api/doc/extract` | POST | 结构化输出（POJO / `@Description` / JSON Mode） |
| Ch08 | `/api/search/semantic` | POST | Embedding 向量检索（默认 ONNX all-MiniLM-L6-v2，0 API Key） |

示例：
```bash
# Ch06 工具调用
curl -X POST http://localhost:8090/api/tools/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"question":"现在几点了？"}'

# Ch08 语义检索（先在 Ch10 上传文档，再查）
curl -X POST http://localhost:8090/api/search/semantic \
  -H "Content-Type: application/json" \
  -d '{"query":"退款政策","topK":3}'
```

---

## 卷三：RAG 检索增强生成深度实战（Ch10~Ch13）

| Ch | 端点 | 方法 | 说明 |
|---|---|---|---|
| Ch10 | `/api/docs/ingest` | POST | 触发 classpath `docs/` 目录全量入库（应用启动时也可自动跑） |
| Ch10 | `/api/docs/upload` | POST（multipart） | 上传单文件入库（PDF / MD / TXT） |
| Ch10 | `/api/docs/stats` | GET | 当前库内文档/分块统计 |
| Ch11 | `chat.embedding.store=redis`（配置项） | — | 一行配置把向量库从内存切到 Redis（需 Redis Stack） |
| Ch12 | `/api/search/multimodal` | POST | 高级 RAG（parent-child + 向量/关键词混合 + rerank） |
| Ch13 | `/api/rag/evaluate` | GET | 对 golden set 跑检索质量评估 |

示例：
```bash
# Ch10 入库
curl -X POST http://localhost:8090/api/docs/ingest

# Ch12 多路检索
curl -X POST http://localhost:8090/api/search/multimodal \
  -H "Content-Type: application/json" \
  -d '{"query":"运费谁承担","topK":5,"mode":"hybrid"}'

# Ch13 评估（依赖 src/test/resources 或代码内 golden set）
curl http://localhost:8090/api/rag/evaluate
```

---

## 卷四：Agent 智能体（Ch14~Ch17）

| Ch | 端点 | 方法 | 说明 |
|---|---|---|---|
| Ch14 | `/api/agent/chat` | POST | 单 Agent（@Agent） |
| Ch14 | `/api/agent/manual` | POST | 手动拼装 Agent 链路 |
| Ch15 | `/api/agentic/parallel` | POST | Agentic 并行编排 |
| Ch15 | `/api/agentic/sequential` | POST | 串行编排 |
| Ch15 | `/api/agentic/loop` | POST | 循环编排 |
| Ch15 | `/api/agentic/conditional` | POST | 条件分支 |
| Ch15 | `/api/agentic/supervisor` | POST | 监督者模式 |
| Ch15 | `/api/agentic/workflow` | POST | 工作流 |
| Ch15 | `/api/agentic/mapper` | POST | 输出映射 |
| Ch16 | `/api/agent/audit` | GET | Agent 审计：工具调用记录 / Token 用量 |
| Ch16 | `/api/agent/engineering/chat` | POST | 工程化 Agent（动态工具加载） |
| Ch17 | `/api/mcp/ask` | POST | MCP 客户端问答（stdio + Streamable HTTP） |
| Ch17 | `/api/mcp/prompts` | GET | 列出 MCP 服务器暴露的 Prompts |
| Ch17 | `/api/mcp/prompt` | GET | 渲染指定 Prompt |
| Ch17 | `/api/mcp/resources` | GET | 列出 MCP 服务器暴露的 Resources |
| Ch17 | `/api/mcp/resource` | GET | 读取指定 Resource |

> ⚠️ Ch17 联调前置：先启动 `mcp-server/`（详见下方"附属模块"）。

---

## 卷五：能力扩展（Ch18）

| Ch | 端点 | 方法 | 说明 |
|---|---|---|---|
| Ch18 | `/api/skills/ask` | POST | Skills 技能封装（`refund-policy` / `troubleshooting` / `mcp-tools`） |

三个技能在 `src/main/resources/skills/` 下，加新技能只需新增目录 + `SKILL.md` + 重启。

---

## 卷五扩展：多模态（Ch09 落地于卷二，但端点独立）

| Ch | 端点 | 方法 | 说明 |
|---|---|---|---|
| Ch09 | `/api/vision/analyze` | POST（multipart） | 图片理解（qwen3-vl-flash） |

---

## 附属模块

### `mcp-server/` —— Node.js MCP 服务端示例（Ch17）

```bash
cd smart-doc-assistant/mcp-server
# 当前仅含 demo-server.mjs（7K，零依赖，纯 Node 标准库 stdio 服务器）
# 直接 node 启动即可，无需 npm install：
node demo-server.mjs
```

> 启动后 Ch17 的 `/api/mcp/ask` 会通过 stdio 子进程与它握手。

---

## 已知未完成

- **测试目录** `src/test/` 当前为空（pom 引入了 `spring-boot-starter-test`，但 0 个用例）。
  这是教程后续要补的工程债，建议优先在 `DocumentSearchService` / `DocumentIngestionService` / `RagEvaluator` / `ChatSessionStore` 上补单测，再加 1~2 个 `@SpringBootTest` 上下文装载测试。

- **同一前缀冲突提醒**：`/api/agent` 同时被 `AgentController` 与 `EngineeringAgentController` 占用，路径前缀通过子路径区分（`/api/agent/chat` vs `/api/agent/engineering/chat`），但若未来新增端点请注意不要撞车。