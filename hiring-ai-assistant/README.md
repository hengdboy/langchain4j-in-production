# hiring-ai-assistant —— AI 招聘官（Ch24/25 综合实战项目）

招聘方侧的候选人全流程自动化：**简历解析 → 决策脱敏 → 岗位匹配评估 →
约面邀请 → 排期出题 → 评价汇总 → offer 跟踪**。

核心设计三件事：

1. **确定性 FSM + LLM 叶子**：12 状态 / 13 事件 / 15 条转换（Spring StateMachine），
   编排权在状态机不在 LLM——Agent 只在叶子节点出结构化结果；
2. **三道护栏**：决策白名单（受保护特征进不了评估 prompt）、注入检测（简历是不可信
   第三方文本）、输出合规护栏（评估理由出现年龄/性别等词直接拦截停人工）；
3. **Bandit 演化 + DPO 样本**：约面话术 6 臂 Thompson Sampling 按回复率自迭代，
   HR 修正 AI 产出沉淀为偏好样本。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)，配套章节 Ch24（编排）、Ch25（护栏与演化）。

## 快速开始

```bash
# 1. 基础设施（MySQL 自动执行 schema.sql；Redis）
cd docker && docker-compose up -d && cd ..

# 2. 模型 key（任选其一）
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 编辑 application-local.yml 填入真实 key（已 gitignore，不会入库）

# 3. 启动（JDK 21）
mvn spring-boot:run

# 4. 一键 demo：四个候选人跑四条路线
curl -X POST http://localhost:8091/api/demo/run \
     -H "Content-Type: application/json" -d '{}'
# 无 key 环境（canned 确定性规则，流程完全一致）：
curl -X POST http://localhost:8091/api/demo/run \
     -H "Content-Type: application/json" -d '{"useRealAgent": false}'
```

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/jobs` | 创建职位 |
| POST | `/api/candidates` | 投递简历（自动解析→脱敏→评估→FSM） |
| GET | `/api/candidates/{id}` | 状态/匹配分/决策画像 |
| GET | `/api/candidates/{id}/events` | 状态变更流水 |
| GET | `/api/candidates/{id}/interview` | 排期与出题 |
| POST | `/api/candidates/{id}/reply` | 候选人回复（约面/offer 同入口） |
| POST | `/api/candidates/{id}/interview/complete` | 面试官提交反馈 |
| POST | `/api/candidates/{id}/review` | HR 复核 ON_HOLD |
| POST | `/api/candidates/{id}/correction` | HR 修正 AI 产出（DPO） |
| POST | `/api/demo/run` | 一键全链路 demo |

## 测试

```bash
mvn test          # 离线测试：状态机模拟器 + 护栏 + 演化 + MCP，无需 key
bash scripts/run-e2e.sh   # 全链路 E2E（需先起服务）
```

## MCP 接入（stdio）

```bash
mvn clean package -Pmcp
java -jar target/hiring-ai-assistant-0.30.0-SNAPSHOT-mcp.jar
```

Claude Desktop / Cursor 配置：

```json
{
  "mcpServers": {
    "hiring-ai-assistant": {
      "command": "java",
      "args": ["-jar", "/绝对路径/hiring-ai-assistant-0.30.0-SNAPSHOT-mcp.jar"]
    }
  }
}
```

暴露 6 个只读查询工具（候选人状态/在招职位/面试安排/邮件列表），最小披露原则：
联系方式与简历原文不出 MCP 边界。

## 注意事项

- **JDK 21 硬约束**：enforcer 锁 `[21,22)`，更高版本 Lombok 失效；
- 真实 key 只放 `application-local.yml`（已 gitignore）；
- 48h/5d 超时在教学版是 2h/5min（`hiring.fsm.*` 可调），
  本地联调可改成秒级观察追发与催办。
