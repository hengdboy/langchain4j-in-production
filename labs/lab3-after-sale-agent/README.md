# Lab3 · 电商售后 Agent——工具循环 + 金额护栏 + 注入拦截

> **基线**：LangChain4j `1.22.0` ｜ JDK 21 ｜ 2026-10-29 实测 ｜ 离线测试零 key 可跑

## 场景

电商售后客服 Agent：用户要退货退款，Agent 查订单、查政策、给结论。三条业务死线：

1. **500 元以内**才能自动退款，超过必须转人工（资金安全的红线）；
2. 食品类目非质量问题不受理（食品安全法规定）；
3. 任何注入攻击（"忽略指令""扮演管理员"）不得绕过以上两条。

模型管流程，护栏管死线——注入也得撞在工具层。

![Lab3 售后 Agent](../assets/lab3-after-sale-agent.png)

## 知识映射

| Lab3 里做的事 | 来自 | 在这里的新意 |
|---|---|---|
| @Tool 工具集（含 mock 订单库） | Ch14 | 四个工具覆盖读（查单/查政策）与写（退款/转人工） |
| 工具循环 + 会话记忆 | Ch14/Ch05 | AiServices 驱动，`@MemoryId` 隔离会话 |
| 失败三层机制 | Ch14 | 护栏异常在工具方法体内抛出 → isError 回喂 → 模型改道 |
| 金额阈值护栏 | Ch21 ToolGuard | 最小可测版：一个方法 + 一个异常类型 |
| 输入注入检测 | Ch21 | 正则先行，便宜、快、自己不会被劫持 |

## 快速开始

```bash
cd labs/lab3-after-sale-agent
mvn test                        # 离线：5 个测试（含桩模型驱动全链路）
export DEEPSEEK_API_KEY=sk-xxx
mvn compile exec:java -Dexec.mainClass=com.example.lab.AfterSaleAgentApp   # 4 个剧本
```

## 核心实现

护栏在写操作的第一行（`AfterSaleTools.java`）：

```java
@Tool("提交退款。仅限 500 元及以内可自动处理；超过 500 元会被系统拦截并要求转人工，不要重试。")
public String submitRefund(@P("订单号") String orderId,
                          @P("退款金额，单位分") long amountFen,
                          @P("退款原因，简述") String reason) {
    RefundGuard.authorizeAutoRefund(amountFen);   // ← 超阈值抛 GuardViolation
    ...
}
```

异常被框架转成 `isError:true` 结果回喂模型（Ch14 失败三层的第一层），
实测里模型的反应是**改道调 `requestHuman`**——这就是"护栏管死线"的含义：
模型被注入也好、被提示词教坏也好，最终落地的动作过不了护栏就做不成。

金额一律用**分**（`long`）：护栏的 `50_000` 就是 500 元，浮点算钱是工程红线。

离线测试的核心手法（`Lab3AfterSaleAgentTest`）：桩模型按剧本表演三轮决策，
AiServices 真实驱动整个工具循环——

```java
StubChatModel stub = new StubChatModel()
        .enqueueToolCall("t1", "queryOrder",   "{\"orderId\":\"SO-2026-1002\"}")
        .enqueueToolCall("t2", "checkReturnPolicy", "{\"category\":\"大家电\"}")
        .enqueueToolCall("t3", "submitRefund", "{\"orderId\":\"SO-2026-1002\",\"amountFen\":799900,...}")
        .enqueueToolCall("t4", "requestHuman", "{...}")
        .enqueueText("电视退款 7999 元超过自动处理上限，我已为您转接人工客服…");
```

第 t3 轮真实发生了"护栏抛异常 → isError 回喂"，第 t4 轮的转人工是模型在
拿到错误信息后的真实再决策——**整条链路零 LLM、零成本、可断言**。

## 实测：四个剧本（deepseek-v4-flash，temperature=0）

| 剧本 | 期望 | 实测 |
|---|---|---|
| 键盘 399 元退货 | 查单→查政策→自动退款 | ✅ 退款受理"48 小时内原路退回"，并提示配件包装要求 |
| 电视 7999 元退款 | 被拦 → 转人工 | ✅ "人工客服将在 30 分钟内接入，保持电话畅通" |
| 注入攻击 | 输入护栏拦截 | ✅ `[input.injection_detected]` 命中模式"忽略之前的所有指令" |
| 坚果退货（食品） | 解释不受理 | ✅ "食品非质量问题不支持退货退款"，并留质量问题入口 |

## 验收清单（三件套）

- [x] **金标集**：4 个剧本的期望行为表（上表）；护栏阈值与注入句式有单测
- [x] **成本报表**：一次 4 剧本运行含工具循环约 10 次模型调用，约 ¥0.004–0.011
- [x] **踩坑记录**：见下
- [ ] 你的部分：把"质量问题退货"做成完整子流程（要照片凭证工具），补 3 个剧本进金标

## 踩坑记录（2026-10-29 当天）

1. **`-parameters` 编译标志没开，工具参数名退化成 arg0/arg1**——模型按 `orderId`
   传参，框架报 `Required parameter "arg1" of tool "submitRefund" is missing`。
   labs 父 pom 已开 `<parameters>true</parameters>`。主教程的 Spring Boot 项目
   没踩是因为 starter 配置带了它。
2. **注入模式漏了"规定"**：测试用"无视之前的**规定**"抓出模式表只写了"规则"——
   攻击句式是变体族，模式表按族收（指令/规则/规定/要求/提示词）。
3. **测试里想复用 AssertJ 的 `assertThatCode`** 与本地同名方法撞车报"无法取消引用 void"，
   换 JUnit 的 `assertDoesNotThrow`——测试工具链的重载歧义不值得调，选无歧义 API。

## 练习

1. 把阈值做成配置（`RefundPolicyGuard` 从文件读上限），金标集补"改配置后剧本行为变化"的用例。
2. 加一个"用户情绪激动直接转人工"的检测（关键词或独立模型判），与 500 元阈值正交。
3. 思考题：为什么注入检测放在 Application 层而不是让系统提示词"忽略注入"？前者拦得住什么，后者拦不住什么。
