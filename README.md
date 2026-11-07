# LangChain4j 生产实战——13 章 + 2 个可运行项目

面向 Java / Spring Boot 开发者的 LangChain4j 教程。不讲 Hello World 罗列 API，每一章都围绕两个**真实可运行**的 Spring Boot 项目展开，正文里的关键数字（检索 MRR、两笔调用合计成本、护栏误杀率、68 个测试）都是标注了验证日期的实跑结果。

> 本仓库收录实战篇 13 章；基础篇（Ch01–09、Ch15）与全书导读只在博客。全书 24 章《langchain4j 从零到生产》在线阅读：[notes.qianyecube.cn/knowledge/langchain4j](https://notes.qianyecube.cn/knowledge/langchain4j/)

- **教程口径**：LangChain4j `1.22.0` + 实验模块 `1.22.0-beta32` ｜ JDK 21（推荐，pom 拦下限）｜ 最后验证 2026-10-28
- **读前必看**：[Ch00 前置速查](chapters/ch00-preflight.md)——环境、学习路径图、全系列约定，15 分钟

> **参与讨论**：环境问题、选型讨论、踩坑分享请到 [Discussions](https://github.com/hengdboy/langchain4j-in-production/discussions)；可复现的 bug 和文档错误请开 [Issues](https://github.com/hengdboy/langchain4j-in-production/issues)。第一次提问前建议先看 [欢迎帖与提问指引](https://github.com/hengdboy/langchain4j-in-production/discussions/1)。

## 学完你能带走什么

这套教程的目标不是让你"会用 LangChain4j"，而是让你在面试和项目里拿得出证据。

- **简历上多两个完整项目**：智能文档问答（RAG 全管线）和 AI 招聘官（6 Agent + 状态机）。两个都能本地跑、有架构图、有测试、有成本账，不是跟着敲一遍就丢的 demo。
- **面试里接得住追问**：检索效果差在哪（11 题金标集、同一份干净库，只切父子分块这一个开关：关掉 MRR 0.417、打开 0.652，其余配置全同）、token 为什么翻倍、虚拟线程下 ThreadLocal 为什么丢、MCP 协议有哪些坑、护栏误杀率怎么量。大多数候选人只能讲 prompt 和接口调用；这里的项目能经得起往下追问三层，每个结论都指得到章节和代码位置。
- **项目里能直接搬**：5 个独立 labs、金标集与 CI 阈值、输入/输出护栏、token 成本 listener、docker-compose 和可视化演示页。

适合有 Java / Spring Boot 基础、想把 AI 能力真正落进业务，或者正在准备面试的开发者。完全零 Java 基础、或者只想要"调个 API 就上线"的速成方案，这套教程不合适。

所有数字都是 LangChain4j 1.22.0 + 指定模型下的实测结果，换版本或换模型请按 Ch13 的方法重测。教程给的是方法和判断，不承诺你的业务指标。

## 章节

**RAG 主线**（文档怎么进库、怎么检索、怎么知道检索得好不好）

| 章 | 主题 | 一句话产出 |
|---|---|---|
| [Ch10 文档管线](chapters/ch10-document-pipeline.md) | 解析 / 分块 / 入库 | PDF+文本双解析器、父子分块入库、元数据盖章（含一个真实存在过的 bug 的修复配方） |
| [Ch11 向量存储](chapters/ch11-vector-store.md) | 选型与切换 | inmemory / Redis 双实现，切模型 = 全量重嵌的代价账 |
| [Ch12 高级 RAG](chapters/ch12-advanced-rag.md) | 父子分块 + 混合检索 + 重排 | 11 题金标集逐题 reciprocal rank 矩阵；同一份干净库只切父子分块开关：MRR 0.417 / 0.652 |
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
| [Ch24 综合实战（上）](chapters/ch24-hiring-automation.md) | AI 招聘官：FSM 编排 | 系统架构总览、12 态 15 转换全景图、事件驱动编排、E2E 12 项断言、68/68 测试全绿 |
| [Ch25 综合实战（下）](chapters/ch25-guardrails-evolution.md) | AI 招聘官：护栏与演化 | 歧视防护三层闸、Bandit 话术自迭代、每 Agent 真机成本账 |

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
| [`hiring-ai-assistant/`](hiring-ai-assistant/) | 8091 | 招聘流程自动化：FSM 状态机 + 6 Agent + 三护栏 + MCP Server + Bandit/DPO + 合规审计（版本 v0.31） |

## 快速开始

两个配套项目二选一（可同时跑，端口 8090 / 8091 互不冲突）。

**路线 A：智能文档问答**（smart-doc-assistant——RAG 全管线）

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

**路线 B：AI 招聘官**（hiring-ai-assistant——FSM + 多 Agent 总装，Ch24/25 配套）

```bash
cd langchain4j-in-production/hiring-ai-assistant
cd docker && docker-compose up -d && cd ..
mvn spring-boot:run
curl -X POST http://localhost:8091/api/demo/run \
     -H "Content-Type: application/json" -d '{"useRealAgent": false}'
```

启动后浏览器打开 <http://localhost:8091/> 是全流程可视化演示页：四条泳道按
FSM 真实时序回放，可看脱敏对照镜（简历原文 vs 模型看到的决策画像）与逐条评估理由。

无 key 可跑（canned 模式）；本地 MySQL/Redis 替代方案、四条路线的预期输出与
MCP 接入见 [hiring-ai-assistant/README.md](hiring-ai-assistant/README.md)。

详细环境要求、按章可选组件（Node / MySQL / Redis）、常见报错速查表见 [Ch00](chapters/ch00-preflight.md)。

## 环境硬约束

- **JDK 21（推荐）**：enforcer 只拦下限 `[21,)`，全书在 21.0.10 上验证。早期旧版 Lombok 在 JDK 22+ 会注解处理失效（一堆"找不到符号 log"），现 Lombok 跟随 Spring Boot（1.18.44），更高版本一般可用
- **LangChain4j 版本成对升级**：稳定模块由 `<dependencyManagement>` 按 `langchain4j.version` 钉住；实验模块（agentic / mcp / skills / guardrails / community-*）只发 beta 线，各自写 `langchain4j.beta.version`
- **Spring Boot 3.5.x**

## License

本仓库采用双许可：

- **代码**（`smart-doc-assistant/`、`hiring-ai-assistant/`、`labs/` 下的源码与构建脚本、`.github/`）：[Apache License 2.0](LICENSE)
- **教程内容**（`chapters/` 章节文字、`assets/` 配图、README 说明文字）：[CC BY-NC-SA 4.0](LICENSE-DOCS.md)——转载需署名、非商用、相同方式共享
- 商业授权请联系仓库所有者；版权归属与边界说明见 [NOTICE](NOTICE)。

## 版本基准

- LangChain4j：`1.22.0`（稳定）+ `1.22.0-beta32`（实验）。截至 2026-10-28 为最新已发布版本；`main` 上的 `1.23.0-beta33-SNAPSHOT` 尚未发布
- 实测模型：DeepSeek `deepseek-v4-flash`（¥1.0/M 输入 + ¥2.0/M 输出，Ch22 有完整成本实测）；DashScope `qwen3.7-flash` 走 OpenAI 兼容端点
- 换模型不影响章节结论的方向，但**实测数字请自己重测**——这是本教程的口径
