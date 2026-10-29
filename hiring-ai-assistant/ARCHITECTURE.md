# ============================================================
# 招聘 AI 助手 - 架构文档
# v0.30 —— Ch24/25 综合实战：AI 招聘官（候选人全流程自动化）
# ============================================================

## §1 场景与边界

招聘方侧的候选人全流程自动化：职位管理 → 简历解析 → 岗位匹配评估 →
约面邀请 → 面试排期与出题 → 评价汇总 → offer 跟踪。

**做什么**：把"简历筛选-约面-面试-offer"这条机械流水线交给 FSM + Agent，
人（HR/面试官）只出现在三个位置——复核待定池、面评反馈、offer 终审。

**不做什么**：不做候选人侧服务（求职者是外部人，不该出现在内网系统里）；
不做薪酬计算与背调决策（背调工具只做演示查询，决策永远是人）。

**与 smart-doc-assistant 的分工**：smart-doc 教 Ch01~Ch22 的单点技术；
本项目把它们拧成一条业务流水线，重点在**编排、护栏、演化**三件事。

## §2 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | Java 21（enforcer 锁 [21,22)） | 更高版本 Lombok 失效 |
| 框架 | Spring Boot 3.5.13 | Web / Validation / Data Redis |
| AI 编排 | LangChain4j 1.22.0（beta32 实验线） | AI Services + @Agent + Guardrails |
| 状态机 | Spring StateMachine 4.0.1 | core + data-redis + autoconfigure |
| ORM | MyBatis-Plus 3.5.9 + MySQL 8.0 | 11 张业务表 |
| KV | Redis 7 | 延迟事件队列（ZSet）+ FSM 快照 |

## §3 状态机（单一权威定义：CandidateStateMachineConfig）

12 状态 / 13 事件 / 15 条转换（11 条带 Guard）。

| # | 源状态 | 事件 | 目标状态 | Guard |
|---|--------|------|----------|-------|
| 1 | APPLIED | SCREEN_PASS | SHORTLISTED | verdict==SHORTLIST |
| 2 | APPLIED | SCREEN_REVIEW | ON_HOLD | verdict==REVIEW |
| 3 | APPLIED | SCREEN_FAIL | REJECTED | verdict==REJECT |
| 4 | ON_HOLD | SCREEN_PASS | SHORTLISTED | humanApproved==true |
| 5 | ON_HOLD | SCREEN_FAIL | REJECTED | humanApproved==false |
| 6 | SHORTLISTED | INVITE_SENT | INVITE_SENT | — |
| 7 | INVITE_SENT | INVITE_REPLIED | INTERVIEW_SCHEDULED | replyDecision==confirmed |
| 8 | INVITE_SENT | INVITE_REPLIED | WITHDRAWN | replyDecision==declined |
| 9 | INVITE_SENT | MAX_ROUNDS_REACHED | CLOSED_NO_RESPONSE | — |
| 10 | INTERVIEW_SCHEDULED | INTERVIEW_DONE | INTERVIEWED | — |
| 11 | INTERVIEWED | EVAL_PASS | OFFER_SENT | evalOutcome==PASS |
| 12 | INTERVIEWED | EVAL_FAIL | REJECTED | evalOutcome==FAIL |
| 13 | OFFER_SENT | OFFER_REPLIED | HIRED | offerDecision==accepted |
| 14 | OFFER_SENT | OFFER_REPLIED | WITHDRAWN | offerDecision==declined |
| 15 | OFFER_SENT | OFFER_EXPIRED | EXPIRED | — |

**两个超时事件不进状态机**：REPLY_TIMEOUT_48H / OFFER_TIMEOUT_5D 由延迟队列
触发后在 CandidateService 前置判断（追发/催办 or 转 MAX_ROUNDS_REACHED /
OFFER_EXPIRED）。原因：Spring StateMachine 的 sendEvent 在 Guard 拒绝时仍返回
true，自循环无法从返回值区分——这是 SDR 版 javap 实测的教训。

**两个易画错的点**（画 FSM 全景图时）：
- 追发不是自循环：INVITE_SENT 上的追发是业务层重发邮件 + 重排延迟事件，
  状态不变、但 candidate_event 里记一条 from==to 的流水；
- EXPIRED 的入边只有一条（OFFER_EXPIRED）——催办在前置判断里完成，
  状态机上没有"催办状态"。

## §4 数据模型（11 张表）

| 表 | 职责 |
|---|---|
| job_position | 职位与 JD（requiredSkills 是决策白名单字段） |
| candidate | 候选人主表；resume_text（含 PII）与 redacted_profile（决策画像）分开存 |
| candidate_event | 状态变更流水（审计+回溯） |
| interview | 面试安排：排期/bookingId/出题/反馈 |
| sent_email / received_email | 邮件往来（round：0=事务邮件，1=首发，2=追发） |
| agent_call | Agent 调用埋点（接 Ch22 ChatModelListener） |
| audit_log | 人事决策合规审计 |
| dpo_sample | DPO 对齐样本（HR 修正 + Bandit 对比） |
| bandit_state / bandit_outcome | Thompson Sampling 状态与待结算队列 |

## §5 延迟事件（Redis ZSet）

Key `hiring:delayed-events`，member=`{candidateId}:{EVENT}`，score=触发时间戳。
worker 每 `hiring.scheduler.event-scan-interval-ms` 扫描一次到期事件，
统一走 CandidateService.processEvent（副作用只在一处）。
Spring StateMachine 4.x 内置 Timer 不能 cancel，所以用 ZSet 自己管。

## §6 mock → 生产替换清单

| 教学版 mock | 生产替换 |
|---|---|
| MailGateway（内存队列） | SMTP/邮件服务商 + 发信审批通道 |
| CalendarTool（内存占位） | 飞书/Google Calendar API |
| BackgroundCheckTool（哈希取样） | 学信网/第三方背调服务（须候选人书面授权） |
| mcp/mock/HiringDirectoryMock | HTTP MCP + 内部只读 API + 鉴权 |
| application-local.yml key | 密钥管理服务（KMS/Vault），不入 yml |
