# LangChain4j 生产实战——12 章 + 2 个可运行项目

面向 Java / Spring Boot 开发者的 LangChain4j 教程。不讲 Hello World 罗列 API，每一章都围绕两个**真实可运行**的 Spring Boot 项目展开，正文里的关键数字（检索 MRR、单次调用成本、护栏误杀率、64 个测试）都是标注了验证日期的实跑结果。

- **教程口径**：LangChain4j `1.22.0` + 实验模块 `1.22.0-beta32` ｜ JDK 21（pom 强制）｜ 最后验证 2026-10-28
- **读前必看**：[Ch00 前置速查](chapters/ch00-preflight.md)——环境、学习路径图、全系列约定，15 分钟

## 章节

**RAG 主线**（文档怎么进库、怎么检索、怎么知道检索得好不好）

| 章 | 主题 | 一句话产出 |
|---|---|---|
| [Ch10 文档管线](chapters/ch10-document-pipeline.md) | 解析 / 分块 / 入库 | PDF+文本双解析器、父子分块入库、元数据盖章（含一个真实存在过的 bug 的修复配方） |
| [Ch11 向量存储](chapters/ch11-vector-store.md) | 选型与切换 | inmemory / Redis 双实现，切模型 = 全量重嵌的代价账 |
| [Ch12 高级 RAG](chapters/ch12-advanced-rag.md) | 父子分块 + 混合检索 + 重排 | 11 题金标集逐题 reciprocal rank 矩阵：MRR 0.417 → 0.652 |
| [Ch13 RAG 评估](chapters/ch13-rag-evaluation.md) | 金标集 + recall/MRR 门禁 | 两代基线对照、CI 阈值怎么定才不会被干净库打脸 |

**Agent 主线**（怎么让 AI 自己干活，并且干得可控）

| 章 | 主题 | 一句话产出 |
|---|---|---|
| [Ch14 Agent 基础](chapters/ch14-agent-basics.md) | @Tool 与工具循环 | 失败三层机制、手动循环漏传工具清单的实测翻车实录 |
| [Ch16 Agent 工程](chapters/ch16-agent-engineering.md) | 动态供给 / 审计 / 并发 | ThreadLocal 上下文被虚拟线程吞掉的全过程与修复 |
| [Ch17 MCP 客户端](chapters/ch17-mcp.md) | 接外部工具生态 | stdio 三原语实测、超时收紧、HTTP client 冲突排除 |
| [Ch18 Skills](chapters/ch18-skills.md) | 按需加载行为手册 | 激活机制 + 资源链逐字验证、与 @Tool/MCP 的三分决策 |

**服务端与横切**（可插在主线任意位置）

| 章 | 主题 | 一句话产出 |
|---|---|---|
| [Ch19 MCP 服务端](chapters/ch19-mcp-server-development.md) | stdio + Streamable HTTP | 官方 SDK 只认数字 id 等 17 条坑点、9 步端点当日实测 |
| [Ch21 Guardrails](chapters/ch21-guardrails.md) | 输入/输出/工具三层防护 | 误杀率实测 10%（银行卡规则只咬 16–19 位）、限流第 31 次触发 |
| [Ch22 可观测性](chapters/ch22-observability.md) | token 计量 + 成本对账 | listener 双注册 token 翻倍、响应模型名归一化两个真 bug 的修复实录 + 告警阈值表 |

**总装**

| 章 | 主题 | 一句话产出 |
|---|---|---|
| [Ch24 企业级实战](chapters/ch24-sdr-ai-assistant.md) | B2B 外贸 SDR 自动化 | FSM 12 状态 13 转换全景图、E2E 全链路实跑、64/64 测试全绿 |

## 实验室（Labs）：5 个小业务场景

跟着教程改过一个大项目之后，还需要一层"自己从零搭、规模可控"的台阶——[`labs/`](labs/) 就是它。每个 lab 独立 Maven 模块、从空目录搭起、**离线测试零 key 可跑**，统一验收三件套：金标集 + 成本报表 + 踩坑记录。

| Lab | 场景 | 巩固 | 实测（2026-10-29） |
|---|---|---|---|
| [Lab1 合同条款问答](labs/lab1-contract-qa/README.md) | 条款+表格混合语料的 RAG | Ch10–13 | 四档切块曲线，MRR 0.370→0.692，门禁进测试 |
| [Lab2 招投标字段抽取](labs/lab2-tender-extract/README.md) | 8 份公告→48 个字段 | Ch07 | 字段级 89.6%，归一化器是主角 |
| [Lab3 电商售后 Agent](labs/lab3-after-sale-agent/README.md) | 退货退款判定 | Ch14/16/21 | 4 剧本全对，7999 元被拦自动转人工 |
| [Lab4 会议室预订 MCP](labs/lab4-meeting-mcp/README.md) | 有写操作的 MCP 服务端 | Ch17/19 | stdio 实测，冲突被拒；零 LLM 成本 |
| [Lab5 简历初筛](labs/lab5-resume-screen/README.md) | PII 脱敏 + 判级 | 总装 | 6 份判级 5/6，"模型只见打码版"有请求级证据 |

## 配套项目

| 项目 | 端口 | 是什么 |
|---|---|---|
| [`smart-doc-assistant/`](smart-doc-assistant/) | 8090 | 智能文档问答：RAG 全管线 + Agent + MCP + Skills + 护栏 + 可观测（版本 v0.19） |
| [`sdr-ai-assistant/`](sdr-ai-assistant/) | 8091 | B2B 外贸 SDR：FSM 状态机 + 5 Agent + MCP Server + Bandit/DPO + GDPR 审计（版本 v1.0） |

## 快速开始

```bash
git clone https://github.com/hengdboy/langchain4j-in-production.git
cd langchain4j-in-production/smart-doc-assistant

# 1. 创建本地配置（该文件已被 .gitignore 排除，key 不进仓库）
cat > src/main/resources/application-local.yml <<'EOF'
langchain4j:
  open-ai:
    chat-model:
      api-key: ${DEEPSEEK_API_KEY:sk-你的key}
EOF

# 2. 启动（本机没有 Redis 时加 memory 参数）
mvn spring-boot:run -Dspring-boot.run.arguments="--chat.memory.backend=memory"

# 3. 第一问（文档问答走 RAG 管线）
curl -s -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"first","message":"七天无理由退货的条件是什么？"}'
```

详细环境要求、按章可选组件（Node / MySQL / Redis）、常见报错速查表见 [Ch00](chapters/ch00-preflight.md)。

## 环境硬约束

- **JDK 21**：enforcer 锁 `[21,22)`，JDK 22+ 下 Lombok 注解处理失效（一堆"找不到符号 log"，增量构建可能假成功，一 clean 就炸）
- **LangChain4j 版本成对升级**：稳定模块由 `<dependencyManagement>` 按 `langchain4j.version` 钉住；实验模块（agentic / mcp / skills / guardrails / community-*）只发 beta 线，各自写 `langchain4j.beta.version`
- **Spring Boot 3.5.x**

## License

代码（两个项目 + 构建脚本）按 Apache-2.0；章节文字与配图按 CC BY-NC-SA 4.0——转载需署名、非商用、相同方式共享。详见 [LICENSE](LICENSE)。

## 版本基准

- LangChain4j：`1.22.0`（稳定）+ `1.22.0-beta32`（实验）。截至 2026-10-28 为最新已发布版本；`main` 上的 `1.23.0-beta33-SNAPSHOT` 尚未发布
- 实测模型：DeepSeek `deepseek-v4-flash`（¥1.0/M 输入 + ¥2.0/M 输出，Ch22 有完整成本实测）；DashScope `qwen3.7-flash` 走 OpenAI 兼容端点
- 换模型不影响章节结论的方向，但**实测数字请自己重测**——这是本教程的口径
