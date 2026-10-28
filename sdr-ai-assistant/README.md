# SDR AI 助手（v1.0 · Phase 6 完成）

> 基于 LangChain4j + Spring StateMachine 的 **B2B 外贸 SDR（Sales Development Rep）自动化系统**。
> Ch24 企业级实战项目（教程卷七）。配套教程：[chapters/ch24-sdr-ai-assistant.md](../chapters/ch24-sdr-ai-assistant.md)（689 行完整实战章节）。

> 基于 LangChain4j + Spring StateMachine 的 **B2B 外贸 SDR（Sales Development Rep）自动化系统**。
> Ch24 企业级实战项目（教程卷七）。

## 一句话介绍

AI 自动完成 B2B 外贸"**找客户 → 背调 → 发邮件 → 多轮跟进 → 意向分级 → 自迭代**"全链路，把外贸业务员从每天发 50 封邮件里解放出来。

## 核心特性

- 🤖 **6 个 SDR Agent 协同工作**：线索挖掘 / 深度背调 / 内容生成 / FSM 触达 / 意向分级 / 自迭代（v0.24 起逐个落地）
- 🧠 **FSM 状态机引擎**（Spring StateMachine）：12 状态 + 10 事件 + 13 条转换规则，MySQL 事实源 + Redis 写透快照
- 📨 **多轮邮件触达**：72 小时静默期监听、最多 3 轮追发、子状态机熔断
- 🎯 **A/B/C/D 4 级意向分级**：自动判级 + 飞书实时通知 + 销售接管
- 🧠 **双闭环自迭代**：客观市场行为 + 人工标注 → Bandit 多臂老虎机 + DPO 对齐样本
- 📊 **完整审计 + GDPR 合规**：所有工具调用、状态变更全链路日志
- 🛡️ **安全防护**：Guardrails 输入清洗 + 注入检测 + PII 脱敏 + 工具配额熔断
- 💰 **成本可观测**：Token 计量 + 成本核算 + 按 Agent 维度 top-N 排行

## 6 个核心阶段（与流程图对应）

```
阶段一：线索挖掘与配额风控       ← LeadSearchAgent（✅）
阶段二：深度背调与 KP 验真       ← LeadResearchAgent（✅ v0.26，3 个 mock 工具）
阶段三：DNA 知识库 + 多语言内容   ← OutreachContentAgent（✅ v0.26）
阶段四：FSM 状态机多轮触达       ← MailGateway + LeadService 调度（✅ v0.26 收拢）
阶段五：回信监听 + A~D 分级       ← IntentClassifierAgent（✅ v0.26）
阶段六：双闭环自迭代 + 公海盘活  ← BanditOptimizer（⏳ v0.28）
```

## 快速开始（开发版）

### 0. 前置条件

- JDK 21
- Maven 3.9+
- Docker Desktop（启动 MySQL + Redis + MailHog）

### 1. 启动基础设施

```bash
cd docker
docker-compose up -d
# 启动：
#  - MySQL 8.0（端口 3306）
#  - Redis 7（端口 6379）
#  - MailHog（端口 1025/8025）
```

### 2. 初始化数据库

```bash
# 等待 MySQL 启动（约 10s），然后建表
docker exec -i sdr-mysql mysql -usdr -psdr_pass sdrdb < src/main/resources/db/schema.sql

# 验证
docker exec -it sdr-mysql mysql -usdr -psdr_pass sdrdb -e "SHOW TABLES;"
# 应输出 7 张表
```

### 3. 配置 API Key

```bash
# 复制模板
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

# 编辑填入真实 key
vim src/main/resources/application-local.yml
```

`application-local.yml` **已被 .gitignore 排除**，真实 key 不会进版本控制。

### 4. 启动应用

```bash
mvn spring-boot:run
```

启动后访问：
- 应用主页：http://localhost:8091
- MailHog UI：http://localhost:8025（看发出的邮件）

### 5. 跑端到端 demo

```bash
# 注入一条询盘 → 看 6 阶段流转 → 检查最终状态
# （useRealAgent=true 需要 DASHSCOPE_API_KEY 真实调 Agent；
#   false 走 canned 确定性流水线，无 key 环境可跑）
curl -X POST http://localhost:8091/api/demo/run \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@example.com","company":"ACME Corp","country":"US","useRealAgent":false}'

# 事件驱动：创建线索即自动推进（背调→内容→发信→STEP1_SENT 或 POOL）
curl -X POST http://localhost:8091/api/leads \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@example.com","company":"ACME Corp","country":"US"}'

# 模拟客户回信（阶段五：自动判级 → INTENT_A/B/C/D）
curl -X POST http://localhost:8091/api/leads/1/reply \
  -H "Content-Type: application/json" \
  -d '{"text":"Please send a quote for 500 IoT gateways and call me."}'

# 查看所有 lead（可按状态过滤）
curl http://localhost:8091/api/leads
curl "http://localhost:8091/api/leads?status=HANDOFF"

# 手动发 FSM 事件（低层控制，如销售接管）
curl -X POST http://localhost:8091/api/leads/1/events \
  -H "Content-Type: application/json" \
  -d '{"event":"SALES_HANDOVER","salesAccepted":true}'
```

### 6. 端到端脚本（v1.0）

```bash
# 跑通 6 阶段 + 3 个横切（Observability / Audit / Bandit）
bash scripts/run-e2e.sh

# CI 模式：仅验证脚本结构（不依赖 App 启动）
bash scripts/run-e2e.sh --no-app
```

脚本会：注入 lead → 触发回信 → 验证 `agent_call` / `audit_log` / `bandit_state` 落库 → 打印汇总。

## 技术栈（生产级，不是教学 mock）

| 层级 | 技术 | 用途 |
|---|---|---|
| **AI 编排** | LangChain4j 1.19.0-beta29 | 6 个 Agent + Ch15 supervisor/parallel/loop |
| **业务流转** | Spring StateMachine 4.0.1 | 12 状态 + 10 事件 + 13 转换（MySQL 事实源 + Redis 快照） |
| **业务数据** | MyBatis-Plus 3.5.9 + MySQL 8.0 | 7 张表，关系型 |
| **运行时 KV** | Spring Data Redis 7.x | 状态机实例 / 限流 / 锁 |
| **邮件 mock** | MailHog（docker） | 邮件收发（生产换 SendGrid/SES）|
| **安全护栏** | LangChain4j Ch21 Guardrails | 反垃圾 + 注入检测 + 配额 |
| **成本监控** | LangChain4j Ch22 Observability | Token 计量 + 行为埋点 |
| **审计** | LangChain4j Ch16 AuditService | GDPR 合规日志 |

## 文档导航

| 文档 | 用途 | 受众 |
|---|---|---|
| **README.md**（本文件）| 项目入口 + 快速开始 | 所有人 |
| **ARCHITECTURE.md** | 架构设计 + Schema + 接口 + Mock策略 | 架构师 / 高级开发 |
| **DEV.md**（待补）| 开发流程 + CI/CD + 故障排查 | 贡献者 |
| [Ch24 教程章节](https://github.com/hengdboy/langchain4j-tutorial/blob/main/chapters/ch24-sdr-ai-assistant.md) | 教学导向 | LangChain4j 学习者 |

## 目录结构

```
sdr-ai-assistant/
├── pom.xml                          ← Maven 配置（Spring Boot 3.5.13 + LangChain4j + StateMachine + MyBatis-Plus）
├── README.md                        ← 本文件
├── ARCHITECTURE.md                  ← 架构设计（FSM / Schema / Mock / 算法）
├── docker/
│   └── docker-compose.yml           ← MySQL + Redis + MailHog
├── src/main/java/com/example/sdr/
│   ├── SdrApplication.java          ← 主入口（@EnableStateMachine + @MapperScan）
│   ├── core/                        ← 核心模型
│   │   ├── LeadStatus.java          ← 12 状态枚举
│   │   ├── FsmEvent.java            ← 10 事件枚举
│   │   ├── LeadContext.java          ← MyBatis-Plus 实体（POJO）
│   │   └── LeadTransitionedEvent.java ← 状态迁移事件（事件驱动编排）
│   ├── agent/                       ← 6 个 SDR Agent（v0.26 已落地 5 个 @Agent）
│   ├── tools/                       ← mock 工具（海关/SMTP/KP）+ MailGateway 邮件网关
│   ├── entity/                      ← 附属实体（sent_email / received_email）
│   ├── service/                     ← LeadService（FSM 入口）+ LeadPipelineService（事件驱动编排）+ DemoService
│   ├── mcp/                         ← 5 个 MCP Server（v0.27 stdio）+ 6 个 @Tool 暴露给外部 LLM 客户端
│   │   ├── McpServerFactory.java    ← 工厂装配 5 个 Tools → McpServer
│   │   ├── McpServerStarter.java    ← 独立 stdio 子进程入口（Claude Desktop 调用）
│   │   ├── tools/                   ← Customs/Smtp/Kp/Email/Feishu 5 个 @Tool POJO
│   │   └── mock/                    ← FeishuMockClient（飞书 webhook mock）
│   ├── evolution/                   ← v0.28 Bandit + DPO 自迭代
│   │   ├── BanditStrategy.java      ← 接口（selectArm/update）
│   │   ├── ThompsonSampling.java    ← 默认实现（Beta 分布采样 + 冷启动轮转）
│   │   ├── BanditOptimizer.java     ← 编排：selectArm 写 bandit_outcome，settle 更新 bandit_state
│   │   ├── DpoSampleWriter.java     ← 写 dpo_sample（3 source：human/bandit/feedback）
│   │   ├── EvolutionScheduler.java  ← @Scheduled 周期结算 bandit_outcome + 写 DPO
│   │   └── CannedContentTemplates.java ← 6 个 arm 模板（formal/casual/concise × quote/sample）
│   ├── guardrail/                   ← v0.29 Guardrails（Ch21）
│   │   ├── PiiRedactionGuardrail.java     ← OutputGuardrail：邮箱/手机/银行卡 脱敏
│   │   └── PromptInjectionGuardrail.java  ← InputGuardrail：11 类高危短语拦截
│   ├── observability/               ← v0.29 Observability（Ch22）
│   │   ├── InstrumentedChatModel.java    ← 包装 ChatModel，挂 ChatModelListener
│   │   ├── CostChatModelListener.java     ← onRequest/Response/Error → agent_call 表
│   │   └── AgentCallContext.java          ← ThreadLocal（leadId + agentName）
│   ├── audit/                       ← v0.29 Audit（Ch16）
│   │   └── AuditService.java              ← @EventListener(LeadTransitionedEvent) → audit_log
│   ├── api/                         ← REST 端点
│   ├── config/                      ← Spring 配置
│   ├── mapper/                      ← MyBatis Mapper
│   └── util/                        ← 工具类
└── src/main/resources/
    ├── application.yml               ← 默认配置
    ├── application-local.yml.example ← 真实 key 模板
    ├── db/schema.sql                 ← 7 张表 DDL
    ├── mappers/                      ← MyBatis XML（待补）
    └── prompts/                      ← Agent Prompt 模板（待补）
```

## 版本演进

| 版本 | 状态 | 主要内容 |
|---|---|---|
| **v0.23** | ✅ Phase 0 | 项目骨架 + ARCHITECTURE.md + 状态/事件枚举 + MyBatis-Plus + Spring StateMachine + Redis |
| **v0.24** | ✅ Phase 1 | FSM 引擎（配置/服务/延迟队列）+ LeadSearchAgent / InquiryAssessorAgent（@Agent）+ 单测 |
| **v0.25** | ✅ Phase 1.5 | **FSM 集成修复**（MySQL 事实源、状态迁移判定、追发链）+ **API 打通**（/api/leads、/api/demo/run）+ lead_event 审计 + 集成测试 |
| **v0.26** | ✅ Phase 2 | **事件驱动编排**（FSM 迁移事件 → 确定性触发 Agent）+ **4 个新 Agent**（背调 LeadResearchAgent / 内容 OutreachContentAgent / 意向分级 IntentClassifierAgent / 发信 MailGateway）+ 3 个 mock 背调工具 + sent_email/received_email 落库 + /api/leads/{id}/reply |
| **v0.27** | ✅ Phase 3 | **5 个 MCP Server**（Customs/Smtp/Kp/Email/Feishu）+ stdio 独立子进程入口（McpServerStarter）+ 6 个 @Tool 暴露给外部 LLM 客户端（Claude Desktop / Cursor）|
| **v0.28** | ✅ Phase 4 | **双闭环自迭代**（Thompson Sampling Bandit 选文案 arm + DPO 样本池）+ 2 张表（bandit_state / bandit_outcome）+ 6 个 arm 模板（3 tone × 2 cta）+ EvolutionScheduler 周期结算 + LeadPipelineService 接入 |
| **v0.29** | ✅ Phase 5 | **横切三件套**（Guardrails + Observability + Audit）+ `agent_call` / `audit_log` 表首次落库 + `InstrumentedChatModel` 包装 ChatModel 挂 listener + `PiiRedactionGuardrail`（email/phone/CC）+ `PromptInjectionGuardrail`（11 类高危短语）+ `CostChatModelListener`（token/latency/error）+ `AuditService` 监听 `LeadTransitionedEvent` |
| **v1.0**（current）| ✅ Phase 6 | **端到端验证 + Ch24 教程章节**（`scripts/run-e2e.sh` 串 6 阶段 + 横切验证 + `/api/observability` `/api/audit` Controller 暴露横切表 + 689 行 Ch24 实战章节完整记录 v0.23→v1.0 演进）|

## MCP Server（v0.27）

5 个 MCP Server 通过 stdio 子进程暴露给外部 LLM 客户端（Claude Desktop / Cursor / Continue）。
同一个业务的 mock 工具对内（SDR Agent）和对外（MCP）共用，避免重复实现。

### 暴露的工具（6 个 @Tool）

| @Tool | 调用底层 | 用途 |
|---|---|---|
| `customsLookup(companyName, country)` | `CustomsMcpTools` | 查公司海关进口记录 |
| `smtpVerify(email)` | `SmtpMcpTools` | 三态邮箱验真 |
| `kpFind(companyName)` | `KpMcpTools` | 找决策人 KP |
| `emailSend(to, subject, body, language)` | `EmailMcpTools` | 发邮件（mock 写入内存） |
| `emailListSent(limit)` | `EmailMcpTools` | 查最近已发邮件 |
| `feishuNotify(webhookUrl, title, content)` | `FeishuMcpTools` | 发飞书通知（mock） |

### Claude Desktop 接入

```bash
# 单独打一个 MCP 入口的可执行 jar
mvn clean package -DskipTests -Pmcp
ls target/sdr-ai-assistant-0.23.0-SNAPSHOT-mcp.jar
```

```json
{
  "mcpServers": {
    "sdr-ai-assistant": {
      "command": "java",
      "args": ["-jar", "/绝对路径/target/sdr-ai-assistant-0.23.0-SNAPSHOT-mcp.jar"]
    }
  }
}
```

> ⚠️ 不要用 `java -cp xxx.jar com.example.sdr.mcp.McpServerStarter`：Spring Boot fat jar 的类在 `BOOT-INF/classes/`，`-cp` 看不见，会直接 `ClassNotFoundException`。也不要用 `--mcp` 参数，`McpServerStarter.main` 不解析参数。

启动后 Claude Desktop 自动发现 6 个工具，可在对话里直接调用：
- "查一下 ACME Corp 在美国的海关进口记录"
- "把 sarah.chen@acme.com SMTP 验真一下"
- "给 sarah 发一封英文开发信"

## 与 smart-doc-assistant 的关系

| 项目 | 版本 | 定位 |
|---|---|---|
| **smart-doc-assistant** | v0.17-v0.22 | LangChain4j 教学示例（Ch01-Ch22）|
| **sdr-ai-assistant**（本项目）| v0.23+ | Ch24 企业实战项目（SDR 自动化）|

两个项目**独立编译、独立部署**，但共享 LangChain4j 1.19.0-beta29 版本。

---

## License

Apache 2.0（沿用 LangChain4j 教程仓库整体协议）

@author langchain4j-tutorial | @since v0.23