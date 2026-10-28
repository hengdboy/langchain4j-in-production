# SDR AI 助手 · 架构设计文档（ARCHITECTURE）

> 版本：v0.23 配套设计文档
> 受众：架构师 / 高级开发者
> 编写原则：先设计再写代码（与 Ch24 章节配套）

## 目录

1. [业务概述](#1-业务概述)
2. [分层架构](#2-分层架构)
3. [FSM 状态机设计](#3-fsm-状态机设计)
4. [数据库 Schema](#4-数据库-schema)
5. [Redis Key 设计](#5-redis-key-设计)
6. [Agent 接口规范](#6-agent-接口规范)
7. [MCP Server 设计](#7-mcp-server-设计)
8. [Mock 策略](#8-mock-策略)
9. [关键算法（Bandit / DPO）](#9-关键算法)
10. [接口稳定性承诺](#10-接口稳定性承诺)

---

## 1. 业务概述

**SDR（Sales Development Rep，销售开发代表）= 用 AI 自动完成 B2B 外贸"找客户 → 背调 → 发邮件 → 跟进 → 意向分级"全链路**。

### 6 个核心阶段（与流程图 1:1 对应）

| 阶段 | 名称 | 核心 Agent | 关键能力 | 状态 |
|---|---|---|---|---|
| 一 | 线索挖掘与配额风控 | LeadSearchAgent | Google Dorks / 全网爬虫 / 配额熔断 | ✅ v0.24 |
| 二 | 深度背调与 KP 验真 | LeadResearchAgent | 海关查询 / SMTP 验真 / KP 挖掘（3 个 mock 工具）| ✅ v0.26 |
| 三 | DNA 匹配 + 多语言内容 | OutreachContentAgent | 多语言模板 / 背调信息定制（RAG 待 v0.29）| ✅ v0.26 |
| 四 | FSM 多轮触达 | MailGateway + LeadService | 72h 监听 / 3 轮追发 / 状态机熔断 | ✅ v0.26（收拢）|
| 五 | 回信监听 + 意向分级 | IntentClassifierAgent | A/B/C/D enum / 回信落库 | ✅ v0.26 |
| 六 | 双闭环自迭代 | BanditOptimizer | Thompson Sampling / DPO 样本池 | ⏳ v0.28 |

---

## 2. 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 0: 接入层（API + MCP）                                │
│  ├─ REST 端点（TradeController）                            │
│  └─ 5 个 MCP Server（Email/Customs/Feishu/Smtp/Linkedin）  │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: AI Agent 层（LangChain4j Ch14/Ch15）               │
│  ├─ Specialist Agent（@Agent 声明式，v0.26 已落地 5 个）    │
│  │   LeadSearch / LeadResearch / OutreachContent /           │
│  │   InquiryAssessor / IntentClassifier                       │
│  ├─ Supervisor（Ch15 supervisorBuilder）调度 6 Agent（v1.0） │
│  └─ AgenticScope（Ch15 黑板）跨 Agent 共享上下文（v1.0）     │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: 业务流转层（Spring StateMachine 轻量用法）        │
│  ├─ LeadService：唯一 FSM 入口（processEvent + 事件发布）   │
│  ├─ LeadPipelineService：事件驱动编排（@EventListener）     │
│  ├─ LeadStateMachine（12 状态 + 10 事件 + 13 转换）       │
│  ├─ LeadStateMachinePersist（Redis）运行时上下文          │
│  └─ StateChangeListener（接 Ch22 埋点，v0.25 起 @Deprecated）│
├─────────────────────────────────────────────────────────────┤
│  Layer 3: 数据层                                              │
│  ├─ MySQL 8.0（业务真相，7 张表）                           │
│  └─ MyBatis-Plus 3.5.x（DAO 与 XML 映射）                  │
├─────────────────────────────────────────────────────────────┤
│  Layer 4: 横切关注点                                         │
│  ├─ Guardrails（Ch21：输入清洗 / 注入检测 / PII 脱敏）    │
│  ├─ Observability（Ch22：ChatModelListener / Token 计量）   │
│  ├─ Audit（Ch16：GDPR 合规审计）                            │
│  └─ Security（Ch19 + Ch21：API Key / 工具白名单）           │
├─────────────────────────────────────────────────────────────┤
│  Layer 5: 基础设施                                            │
│  ├─ Redis 7.x（运行时 KV：状态机 / ChatMemory / 限流）    │
│  ├─ MySQL 8.0（业务数据）                                  │
│  └─ MailHog（开发环境邮件 mock，docker-compose 启）         │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. FSM 状态机设计

### 3.1 状态枚举（12 个）

> ⚠️ v0.25 勘误：文档此前写"11 个"，实际枚举/测试均为 **12 个**（4 流程 + 4 意向 + 3 沉淀 + 1 终态）。

```java
public enum LeadStatus {
    // 流程中
    RAW,            // 新建线索
    QUALIFIED,      // 背调通过
    READY,          // 开发信就绪
    STEP1_SENT,     // 第1轮触达中

    // 意向分级（阶段五）
    INTENT_A,       // A 高意向
    INTENT_B,       // B 中意向
    INTENT_C,       // C 低意向
    INTENT_D,       // D 无效

    // 沉淀
    POOL,           // 公海待补充
    BOUNCE,         // 投递失败
    DORMANT,        // 沉睡（90 天培育期）

    // 完成
    HANDOFF         // 销售接管
}
```

### 3.2 事件枚举（10 个）

```java
public enum FsmEvent {
    QUALIFY_OK,          // 背调通过（→ QUALIFIED）
    QUALIFY_FAIL,        // 背调失败（→ POOL）
    CONTENT_READY,       // 内容就绪（→ READY）
    EMAIL_SENT,          // 邮件已发送（→ STEP1_SENT）
    EMAIL_REPLIED,       // 客户回信（→ INTENT_A/B/C/D 之一）
    EMAIL_BOUNCED,       // 邮件退回（→ BOUNCE）
    TIMEOUT_72H,         // 72h 超时（→ 追发下一轮）
    MAX_ROUNDS_REACHED,  // 3 轮追发完毕（→ DORMANT）
    SALES_HANDOVER,      // 销售接管（→ HANDOFF）
    RE_CRAWL_TRIGGER     // 90 天盘活（→ RAW）
}
```

### 3.3 状态转换表（13 条规则）

> ⚠️ v0.25 修订：`TIMEOUT_72H` 自循环（STEP1_SENT→STEP1_SENT）已移除——
> Spring StateMachine 的 Guard 拦截无法从 `sendEvent` 返回值区分（javap 核实，
> guard-blocked 的 ResultType 仍是 ACCEPTED），自循环状态不变不可判。
> 追发判断改由 `LeadService.handleTimeout` 前置处理：
> `sentRounds < max` → 追发重排；`≥ max` → 发 `MAX_ROUNDS_REACHED` 转 DORMANT。

| 当前状态 | 事件 | 下一状态 | Guard 条件 | Action 钩子 |
|---|---|---|---|---|
| RAW | QUALIFY_OK | QUALIFIED | smtpVerified=true | 写 lead_event |
| RAW | QUALIFY_FAIL | POOL | smtpVerified=false | 写 lead_event |
| QUALIFIED | CONTENT_READY | READY | outreach != null | 写 lead_event |
| READY | EMAIL_SENT | STEP1_SENT | sentRounds < 3 | 启动 72h 监听定时器 |
| STEP1_SENT | EMAIL_REPLIED | INTENT_A | LLM 判定 = A | 飞书通知 + 写 lead_event |
| STEP1_SENT | EMAIL_REPLIED | INTENT_B | LLM 判定 = B | 写 lead_event + 生成待办 |
| STEP1_SENT | EMAIL_REPLIED | INTENT_C | LLM 判定 = C | 写 lead_event + 进入培育 |
| STEP1_SENT | EMAIL_REPLIED | INTENT_D | LLM 判定 = D | 写 lead_event + 加入黑名单 |
| STEP1_SENT | EMAIL_BOUNCED | BOUNCE | — | 加入全员共享黑名单 |
| STEP1_SENT | MAX_ROUNDS_REACHED | DORMANT | sentRounds == max（业务层判断）| 启动 90 天培育期 |
| INTENT_A | SALES_HANDOVER | HANDOFF | — | 通知销售 |
| INTENT_B | SALES_HANDOVER | HANDOFF | 销售认领 | 通知销售 |
| HANDOFF | (终态) | — | — | — |
| DORMANT | RE_CRAWL_TRIGGER | RAW | 90天到期 | 重新背调 |

### 3.4 Spring StateMachine 轻量用法

```java
@Configuration
@EnableStateMachineFactory        // v0.25：改用 Factory（@EnableStateMachine 不提供 Factory Bean，启动报 NoSuchBean）
public class LeadStateMachineConfig
        extends StateMachineConfigurerAdapter<LeadStatus, FsmEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<LeadStatus, FsmEvent> states) {
        states.withStates()
              .initial(LeadStatus.RAW)
              .states(EnumSet.allOf(LeadStatus.class))
              .end(LeadStatus.HANDOFF);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<LeadStatus, FsmEvent> tx) {
        tx.withExternal()
          .source(LeadStatus.RAW).target(LeadStatus.QUALIFIED)
          .event(FsmEvent.QUALIFY_OK)
          .guard(leadVerifiedGuard());
    }
}
```

### 3.5 持久化策略

```
StateMachine 实例（运行时上下文）→ MySQL lead.status 是唯一事实源（v0.25）
  - 每次处理事件前：以 DB 状态 resetStateMachine，再发事件
  - 转换成功：新状态写回 MySQL + 写 lead_event 审计
  - Redis 仅作写透快照（审计/观测，非运行依赖）
  - Key: sm:{leadId}（v0.25 实测 data-redis ContextRepository 直接用 machineId 作 key，无 sm: 前缀）
  - TTL: 1 小时（spring-statemachine-data-redis 内置）
```

### 3.6 时间事件 vs 立即事件（关键设计）

状态机本身**只懂事件，不懂时间**。SDR 的 3 个"时间事件"必须**外部触发器**生成。

| 事件 | 性质 | 触发方式 |
|---|---|---|
| `EMAIL_SENT` | 立即 | 邮件发送成功后立即触发 |
| `EMAIL_REPLIED` | 立即 | Webhook 接收回信后立即触发 |
| `EMAIL_BOUNCED` | 立即 | SMTP 退回事件触发 |
| `QUALIFY_OK` / `QUALIFY_FAIL` | 立即 | Agent 处理完毕触发 |
| **`TIMEOUT_72H`** | **时间** | **外部 scheduler 触发** |
| **`MAX_ROUNDS_REACHED`** | **时间** | **同 TIMEOUT_72H 触发（连续 3 轮后）** |
| **`RE_CRAWL_TRIGGER`** | **时间** | **外部 scheduler 触发（90 天盘活）** |

### 3.7 时间事件实现：Redis ZSet 延迟队列 + @Scheduled 扫描

**不用 Spring StateMachine 内置 Timer** 的原因：
- ❌ 4.x Timer 事件**不能 reschedule / cancel**
- ❌ 客户中途回信了，72h 定时器还在跑
- ❌ 重启后 Timer 状态丢失

**用 Redis ZSet 方案**（可取消 / 可重置）：

```java
@Component
public class DelayedEventQueue {
    private final StringRedisTemplate redis;
    private static final String ZSET_KEY = "sdr:delayed-events";

    /** 调度一个事件在未来某时刻触发 */
    public void schedule(String leadId, FsmEvent event, Instant fireAt) {
        String value = leadId + ":" + event.name();
        redis.opsForZSet().add(ZSET_KEY, value, fireAt.toEpochMilli());
    }

    /** 取消调度（客户回信时调用） */
    public void cancel(String leadId, FsmEvent event) {
        String value = leadId + ":" + event.name();
        redis.opsForZSet().remove(ZSET_KEY, value);
    }

    /** 取已到期事件（原子地） */
    public List<String> takeDueEvents() {
        long now = System.currentTimeMillis();
        Set<String> due = redis.opsForZSet().rangeByScore(ZSET_KEY, 0, now);
        if (due == null || due.isEmpty()) return List.of();
        redis.opsForZSet().remove(ZSET_KEY, due.toArray());
        return new ArrayList<>(due);
    }
}

@Component
@RequiredArgsConstructor
public class DelayedEventWorker {
    private final DelayedEventQueue queue;
    private final LeadStateMachineService fsmService;
    private final SdrProperties props;

    @Scheduled(fixedDelayString = "${sdr.scheduler.step1-scan-interval-ms}")
    public void processStep1Timeouts() {
        List<String> events = queue.takeDueEvents();
        for (String value : events) {
            String[] parts = value.split(":");
            Long leadId = Long.parseLong(parts[0]);
            FsmEvent event = FsmEvent.valueOf(parts[1]);
            log.info("⏰ [DelayedEvent] lead={} event={}", leadId, event);
            leadService.processEvent(leadId, event);   // v0.25：统一走业务层（TIMEOUT_72H 内部路由 handleTimeout）
        }
    }
}
```

**关键场景：客户中途回信时取消**

```java
public void onEmailReplied(Long leadId) {
    // 1. 取消 72h 定时器（关键！避免 50h 后误触发追发）
    delayedQueue.cancel(leadId.toString(), FsmEvent.TIMEOUT_72H);
    // 2. 触发 EMAIL_REPLIED 事件（v0.25：统一走 LeadService，内部同时负责取消定时器）
    leadService.processEvent(leadId, FsmEvent.EMAIL_REPLIED, Map.of("intentGrade", "A"));
}
```

### 3.8 所有"可调时间"集中管理（SdrProperties）

教学/测试时把 72h 改成 2 分钟太常见——**时间配置必须可调**。

```java
@ConfigurationProperties(prefix = "sdr")
public class SdrProperties {
    private Fsm fsm = new Fsm();
    private Scheduler scheduler = new Scheduler();
    private Agent agent = new Agent();
    private Bandit bandit = new Bandit();

    public static class Fsm {
        private long stepWaitTimeoutMs = 7_200_000L;   // 教学版 2h
        private long dormantActivationMs = 300_000L;   // 教学版 5min
        private int maxFollowupRounds = 3;
    }
    public static class Scheduler {
        private long step1ScanIntervalMs = 60_000L;     // 1 min
        private long dormantScanIntervalMs = 3_600_000L; // 1h
        private long banditEvalIntervalMs = 600_000L;   // 10 min
    }
    public static class Agent {
        private long callTimeoutMs = 30_000L;
        private int maxRetries = 2;
        private long retryIntervalMs = 2_000L;
    }
    public static class Bandit {
        private String strategy = "thompson-sampling";
        private double explorationFactor = 0.1;
        private int retrainThreshold = 100;
    }
}
```

**对应 application.yml**：
```yaml
sdr:
  fsm:
    step-wait-timeout-ms: 7200000    # 教学版 2h（生产 72h）
    dormant-activation-ms: 300000    # 教学版 5min（生产 90d）
    max-followup-rounds: 3
  scheduler:
    step1-scan-interval-ms: 60000
    dormant-scan-interval-ms: 3600000
  agent:
    call-timeout-ms: 30000
    max-retries: 2
  bandit:
    strategy: thompson-sampling
    exploration-factor: 0.1
    retrain-threshold: 100
```

**好处**：
- 改一个值不需要翻代码
- 测试用 `@TestPropertySource` 覆盖
- 教学（2h）/ 预发（24h）/ 生产（72h）可分层配置

---

## 4. 数据库 Schema

### 4.1 表清单（7 张业务表）

| 表名 | 主键 | 用途 | 估算行数 |
|---|---|---|---|
| `lead` | id (BIGINT) | 销售线索主表 | 1k+ |
| `lead_event` | id | 状态变更流水（审计 + 回溯）| lead×20 |
| `agent_call` | id | Agent 调用记录（埋点）| lead×10 |
| `sent_email` | id | 邮件发送历史 | lead×5 |
| `received_email` | id | 客户回信 | lead×2 |
| `audit_log` | id | GDPR 合规审计 | lead×20 |
| `dpo_sample` | id | DPO 对齐样本 | 持续累积 |

### 4.2 lead（销售线索主表）

```sql
CREATE TABLE lead (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_email VARCHAR(255) NOT NULL,
    company_name VARCHAR(255),
    country_code CHAR(2),
    status VARCHAR(20) NOT NULL,
    key_person VARCHAR(100),
    key_person_email VARCHAR(255),
    smtp_verified TINYINT(1) DEFAULT 0,
    selected_language VARCHAR(10),
    sent_rounds INT DEFAULT 0,
    intent_grade VARCHAR(20),
    last_sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email (customer_email),
    KEY idx_status (status),
    KEY idx_country (country_code),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.3 lead_event（状态变更流水）

```sql
CREATE TABLE lead_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    event VARCHAR(30) NOT NULL,
    actor VARCHAR(50) COMMENT 'system / agent:xxx / human:xxx',
    reason TEXT,
    metadata JSON COMMENT 'LeadContext.extras 的快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead (lead_id, created_at),
    KEY idx_event (event)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.4 agent_call（Agent 调用埋点，对应 Ch22 ChatModelListener）

```sql
CREATE TABLE agent_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    agent_name VARCHAR(50) NOT NULL,
    tools_used JSON COMMENT '调用的工具列表',
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    duration_ms INT,
    status VARCHAR(20) COMMENT 'success / failed / timeout',
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead_agent (lead_id, agent_name),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.5 sent_email / received_email（邮件历史）

```sql
CREATE TABLE sent_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    round_no INT NOT NULL COMMENT '第几轮（1-3）',
    from_email VARCHAR(255) NOT NULL,
    to_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    language VARCHAR(10),
    smtp_message_id VARCHAR(255),
    status VARCHAR(20) COMMENT 'sent / bounced / opened',
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead (lead_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE received_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT,
    from_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    matched_lead_id BIGINT COMMENT 'SMTP 解析后匹配到的 lead',
    intent_grade VARCHAR(20) COMMENT '阶段五判定后回填',
    KEY idx_from (from_email),
    KEY idx_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.6 audit_log（GDPR 合规审计，对应 Ch16）

```sql
CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(50) NOT NULL COMMENT 'system / user_id / agent:xxx',
    action VARCHAR(50) NOT NULL COMMENT 'create / read / update / delete / export',
    resource_type VARCHAR(30) NOT NULL COMMENT 'lead / email / dpo_sample',
    resource_id BIGINT,
    lead_id BIGINT COMMENT '关联线索',
    detail JSON,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_actor (actor, created_at),
    KEY idx_lead (lead_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.7 dpo_sample（DPO 对齐样本，阶段六用）

```sql
CREATE TABLE dpo_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt TEXT NOT NULL,
    chosen TEXT NOT NULL COMMENT '人工标注"好的回复"',
    rejected TEXT NOT NULL COMMENT '人工标注"不好的回复"',
    source VARCHAR(30) COMMENT 'human / bandit / feedback',
    metadata JSON,
    used_in_training TINYINT(1) DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_used (used_in_training, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 5. Redis Key 设计

| Key 模式 | 用途 | TTL | 写入方 | 读取方 |
|---|---|---|---|---|
| `sm:{leadId}`（实测为 machineId 裸 key）| StateMachine 写透快照 | 1h | LeadStateMachinePersist | 观测/审计 |
| `lock:lead:{leadId}` | 状态变更分布式锁 | 30s | 业务层 | 业务层（CAS）|
| `chat-memory:{userId}` | Ch05 ChatMemory 持久化 | 30d | ChatMemoryStore | ChatMemory |
| `rate-limit:{api-key}:{minute}` | Ch21 限流 | 60s | ToolGuard | ToolGuard |
| `mcp-session:{clientId}` | Ch19 MCP 会话 | 30m | MCP Server | MCP Server |
| `feedback-buffer:{userId}` | 阶段六人工标注缓冲 | 5m | API 层 | Bandit 评估 |
| `dedup:email:{email-hash}` | 阶段一去重（黑名单快速查）| 永久 | LeadSearchAgent | LeadSearchAgent |

**为什么 Redis 存这些**：
- 高频读写（每次状态变更查一次）
- TTL 自动清理
- 不需要复杂查询
- 分布式场景下共享

---

## 6. Agent 接口规范

### 6.1 BaseAgent 抽象类

```java
public abstract class BaseAgent {
    protected abstract LeadContext execute(LeadContext ctx);
}
```

### 6.2 Agent 命名规范

- 类名：`XxxAgent`（如 `LeadSearchAgent`）
- Spring Bean 名：`<lowerCamel>XxxAgent`（如 `leadSearchAgent`）
- MCP 工具命名：`<domain>_<action>`（如 `email_send`、`customs_lookup`）

### 6.3 Agent 与 FSM 集成（v0.26 事件驱动编排）

**编排策略：确定性 FSM 骨架 + LLM 只做叶子。** 不是 LLM 自主编排——72h 定时 / 3 轮追发 / A-D 路由都是硬规则，由状态机决定"下一步去哪"；LLM 只负责每个阶段内部的判断/生成，输出结构化结果回喂为下一个 FSM 事件。

```java
// ① 唯一状态机入口：accepted 转换后发布 LeadTransitionedEvent
public class LeadService {
    public LeadContext processEvent(Long leadId, FsmEvent event, Map<String,Object> overrides) {
        LeadContext lead = leadMapper.selectById(leadId);   // MySQL 事实源
        if (event == FsmEvent.TIMEOUT_72H) {
            return handleTimeout(leadId);                    // 追发/转 DORMANT 业务前置判断
        }
        applyOverrides(lead, overrides);                     // intentGrade/smtpVerified 等
        LeadStatus newStatus = fsmService.sendEvent(lead, event, overrides); // 返回新状态或 null
        if (newStatus == null) return lead;                  // Guard 拒绝 → 不改库
        lead.setStatus(newStatus);
        handleSideEffects(event, lead, oldStatus, newStatus);// 调度/取消 72h、轮次、审计
        leadMapper.updateById(lead);
        recordEvent(lead, event, oldStatus, newStatus);      // lead_event 审计
        eventPublisher.publishEvent(new LeadTransitionedEvent(leadId, oldStatus, newStatus, event));
        return lead;
    }
}

// ② 事件驱动编排器：按新状态确定性触发下一个 Agent
public class LeadPipelineService {
    @EventListener
    public void onTransition(LeadTransitionedEvent ev) {
        switch (ev.to()) {
            case QUALIFIED -> generateContentAndReady(ev.leadId()); // → 内容 Agent → CONTENT_READY
            case READY     -> sendFirstEmail(ev.leadId());          // → MailGateway → EMAIL_SENT
            default        -> { }                                   // STEP1_SENT/POOL/DORMANT/HANDOFF：等待外部事件
        }
    }

    // 外部事件入口：
    //  ingest(req)   新建线索 → LeadResearchAgent 背调 → QUALIFY_OK/FAIL → 自动推进到 STEP1_SENT 或 POOL
    //  onReply(id,text) 客户回信 → IntentClassifierAgent 判级 → EMAIL_REPLIED(intentGrade)
}

// ③ 每个 Agent = 单方法 @Agent 接口（agentBuilder 校验恰好一个 @Agent 方法），
//    在 SdrAgentConfig 装配：LeadResearchAgent 挂 3 个 mock 工具（海关/SMTP/KP），
//    其余纯 LLM 生成/分类。
```

**链路：** `ingest → 背调(QUALIFY_OK) → QUALIFIED → [事件]内容(CONTENT_READY) → READY → [事件]发信(EMAIL_SENT) → STEP1_SENT → onReply → EMAIL_REPLIED(intentGrade) → INTENT_x → SALES_HANDOVER → HANDOFF`

---

## 7. MCP Server 设计（v0.27 已落地）

5 个 MCP Server，全部 stdio 传输（生产可改 HTTP）—— 同一个业务的 mock 工具对内（SDR Agent）和对外（MCP）共用：

| MCP Server（@Tool 类） | 暴露的 @Tool | 复用 v0.26 能力 |
|---|---|---|
| `CustomsMcpTools` | `customsLookup` | CustomsLookupTool |
| `SmtpMcpTools` | `smtpVerify` | SmtpVerifyTool |
| `KpMcpTools` | `kpFind` | KpFinderTool |
| `EmailMcpTools` | `emailSend` / `emailListSent` | MailGateway（内存队列）|
| `FeishuMcpTools` | `feishuNotify` | FeishuMockClient |

**架构**：
- 每个 `*McpTools` 是纯 POJO（无 `@Component`），方法用 `@Tool`+ `@P` 注解
- `McpServerFactory.create(...)` 装配全部 5 个 Tools + `McpImplementation(serverInfo)` → `McpServer`
- `McpServerStarter.main` 是**独立 stdio 入口**：`AnnotationConfigApplicationContext` 精准加载（不拉 Spring Boot web、不拉 DataSource），`StdioMcpServerTransport` 阻塞主线程直到客户端断开
- **关键**：日志必须走 `System.err`，否则污染 stdout 上的 JSON-RPC 协议流
- 工具白名单 `McpServerFactory.TOOL_NAMES` 是单一真相源

**客户端接入**：Claude Desktop / Cursor / Continue 配 `mcpServers` 即可调用，无需写客户端代码。

---

## 8. Mock 策略

### 8.1 真实依赖 vs Mock 对照表

| 外部系统 | 真实 | 开发 mock | 生产替换 |
|---|---|---|---|
| SMTP 邮件 | SendGrid/AWS SES | MailHog（docker）| 改 MailSender 配置 |
| 海关数据 | 商务部 API | in-memory 数据集 | 改 CustomsMcpServer 实现 |
| 飞书 Webhook | 飞书开放平台 | Mock HTTP server | 改 FeishuMcpServer 实现 |
| LinkedIn 爬虫 | LinkedIn API | 静态 JSON | 改 LinkedinMcpServer 实现 |
| 数据库 | MySQL 8.0 | MySQL 8.0（docker）| — |
| Redis | Redis 7.x | Redis 7.x（docker）| — |

> **v0.26 已落地代码内 mock**：`CustomsLookupTool`（海关记录）/ `SmtpVerifyTool`（SMTP 三态验真）/ `KpFinderTool`（KP 挖掘）挂到 LeadResearchAgent 作为 `@Tool`；`MailGateway`（内存邮件队列）承担阶段四发信。这些与上面 MCP 层 mock 对应，MCP 化（v0.27）时可直接复用其规则。

### 8.2 Mock 接口稳定性原则

所有 mock 实现**完整实现真实接口**——切换到真实服务只需改 `@Configuration` Bean，**业务代码零修改**。

### 8.3 docker-compose.yml（开发环境）

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: sdr_root
      MYSQL_DATABASE: sdrdb
      MYSQL_USER: sdr
      MYSQL_PASSWORD: sdr_pass
    ports: ["3306:3306"]
    volumes: [mysql-data:/var/lib/mysql]
  
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  
  mailhog:
    image: mailhog/mailhog
    ports: ["1025:1025", "8025:8025"]

volumes:
  mysql-data:
```

---

## 9. 关键算法

### 9.1 Bandit 多臂老虎机（v0.28 已落地）

**架构（确定性编排 + Bandit 在叶子层）**：

```
LeadPipelineService.generateContentAndReady (QUALIFIED)
   │
   ├─ BanditOptimizer.selectArm(lead) → 选 arm_id
   │     └─ ThompsonSampling.selectArm(arms)        // Beta(α,β) 采样
   │           └─ 冷启动：totalPulls < 5 轮转探索
   │
   ├─ OutreachContentAgent.generate / CannedContentTemplates.forArm(armId)
   │     └─ 按 arm_id 选 tone (formal/casual/concise) + cta (quote/sample)
   │
   └─ bandit_outcome 表插入 (arm_id, prompt, output)
        ↓
EvolutionScheduler.@Scheduled (10 min) settle
   ├─ bandit_state 表更新 α/β
   └─ dpo_sample 表写 (chosen, rejected) — bandit 对比样本
```

**臂空间**（教学版6个）：`{formal|casual|concise} × {quote|sample} × email = 6 arm`

**reward 映射**（lead 最终状态 → 0~1）：
- INTENT_A / HANDOFF → 1.0
- INTENT_B → 0.6
- INTENT_C → 0.2
- DORMANT → 0.1
- INTENT_D / POOL → 0.0

### 9.2 DPO 对齐样本构建（v0.28 已落地）

```
1. Bandit 对比（自动，主流水）：EvolutionScheduler 结算 bandit_outcome 时
   写 dpo_sample(source='bandit', chosen=本臂产出, rejected=baseline 占位)
2. Human / Feedback 标注（接口已留）：DpoSampleWriter.write(..., source='human')
3. 训练时从 dpo_sample 取样（v0.28 不做训练，只暴露样本）
4. DPO loss = log σ(β · log(π_chosen/π_ref)) - log σ(β · log(π_rejected/π_ref))
```

详见附录 H。

## 10. 接口稳定性承诺

### 10.1 不会破坏的接口

- `LeadStatus` / `FsmEvent` 枚举（外部依赖）
- `BaseAgent.execute(LeadContext)`（所有 Agent 父接口）
- `LeadStateMachineConfig` 状态转换表（业务唯一）
- 7 张表的 DDL（业务数据契约）

### 10.2 可能变化的接口

- `extras` Map（Agent 之间临时数据）—— 类型未约束
- MCP 工具的参数细节（可增加字段）

### 10.3 弃用流程

任何破坏性变更必须：
1. 先在 `ARCHITECTURE.md` 写弃用说明
2. 保留旧版本至少 1 个 minor 版本
3. 在 changelog 标注 `@Deprecated(since="v0.XX")`

### 10.4 v0.29 横切架构图（Guardrails + Observability + Audit）

```
┌──────────────────────────────────────────────────────────────┐
│   LeadPipelineService.doResearch/doGenerateContent/doClassify│
│   ┌──────────────────────────────────────────────────────┐   │
│   │ AgentCallContext.set(leadId, "AgentName")            │   │
│   │   ↓                                                  │   │
│   │ AgentBuilder 构造的 Agent                            │   │
│   │   ↓                                                  │   │
│   │ InstrumentedChatModel.chat(req)                      │   │
│   │   ├─→ CostChatModelListener.onRequest(ctx)           │   │
│   │   │     └─ attributes.put(startTime)                 │   │
│   │   ├─→ delegate.chat(req)                             │   │
│   │   │     └─→ PromptInjectionGuardrail.validate(...)   │   │
│   │   │           └─ FAIL → throw InputGuardrailException│   │
│   │   │     └─→ LLM call                                │   │
│   │   ├─→ PiiRedactionGuardrail.validate(aiMsg)          │   │
│   │   │     └─ redact email/phone/cc                     │   │
│   │   └─→ CostChatModelListener.onResponse(ctx)          │   │
│   │         └─ agent_call 表写入                         │   │
│   │ AgentCallContext.clear()                             │   │
│   └──────────────────────────────────────────────────────┘   │
│                                                              │
│   LeadService.publishEvent(LeadTransitionedEvent)             │
│       ↓                                                      │
│   AuditService.@EventListener                                │
│       └─→ audit_log 表写入                                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 附录 A：与 LangChain4j 教程章节映射

| LangChain4j 章节 | SDR 项目位置 |
|---|---|
| Ch03 Prompt | LeadSearchAgent（CoT 生成 Dorks）|
| Ch05 ChatMemory | OutreachFSM 多 tool 上下文 |
| Ch06 @Tool | 11 个 MCP 工具 |
| Ch07 结构化输出 | IntentClassifier（enum A/B/C/D）|
| Ch08-12 RAG | DNA 知识库 + 黑名单去重 |
| Ch14 Agent | 6 个 SDR Agent |
| Ch15 编排 | supervisor / parallel / conditional / loop |
| Ch16 工程化 | GDPR 审计日志 |
| Ch17/19 MCP | 5 个 MCP Server |
| Ch21 Guardrails | 反垃圾 + 注入检测 + 配额熔断 |
| Ch22 Observability | Token 计量 + 行为埋点 |

详见 `chapters/ch24-sdr-ai-assistant.md`。

---

*本架构文档是设计契约，写代码前必须 review 完整版本。*