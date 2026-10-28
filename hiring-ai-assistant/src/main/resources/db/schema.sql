-- ============================================================
-- 招聘 AI 助手 - 数据库 Schema（ARCHITECTURE.md §4）
-- 11 张业务表
--
-- 初始化方式：
--   docker exec -i hiring-mysql mysql -uhiring -phiring_pass hiringdb < schema.sql
--
-- 或通过 docker-entrypoint-initdb.d 自动初始化（schema.sql 已挂载）
-- ============================================================

-- ============== 1. job_position（职位表） ==============
CREATE TABLE IF NOT EXISTS job_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    department VARCHAR(100),
    jd_text TEXT COMMENT 'JD 全文',
    required_skills JSON COMMENT '必备技能（决策白名单字段）',
    headcount INT DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职位表';

-- ============== 2. candidate（候选人主表） ==============
-- resume_text 存简历原文（含 PII）：教学版讨论"数据不出内网"，生产建议对象存储+加密+字段级权限
CREATE TABLE IF NOT EXISTS candidate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(100),
    phone VARCHAR(30),
    job_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL COMMENT 'FSM 状态：APPLIED/SHORTLISTED/INVITE_SENT/INTERVIEW_SCHEDULED/INTERVIEWED/OFFER_SENT/HIRED/REJECTED/WITHDRAWN/CLOSED_NO_RESPONSE/EXPIRED/ON_HOLD',
    match_score INT COMMENT '匹配分 0-100',
    match_verdict VARCHAR(20) COMMENT 'SHORTLIST / REVIEW / REJECT',
    match_reasons JSON COMMENT '评估理由（拒信可解释性数据源）',
    redacted_profile JSON COMMENT '决策画像（DecisionFieldFilter 白名单过滤后，评估 Agent 唯一可见的画像）',
    resume_text MEDIUMTEXT COMMENT '简历原文（含 PII；只进库不出决策）',
    invite_rounds INT DEFAULT 0 COMMENT '已发邀请轮次（1=首发，2=追发）',
    offer_reminder_sent TINYINT(1) DEFAULT 0 COMMENT 'offer 是否已催办',
    extras JSON COMMENT 'Agent 共享临时数据',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email_job (email, job_id),
    KEY idx_status (status),
    KEY idx_job (job_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人主表';

-- ============== 3. candidate_event（状态变更流水） ==============
CREATE TABLE IF NOT EXISTS candidate_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    from_status VARCHAR(30) COMMENT '变更前状态（自循环追发时与 to_status 相同）',
    to_status VARCHAR(30) NOT NULL COMMENT '变更后状态',
    event VARCHAR(30) NOT NULL COMMENT '触发事件',
    actor VARCHAR(50) COMMENT 'system / agent:xxx / human:xxx',
    reason TEXT,
    metadata JSON COMMENT 'CandidateContext.extras 快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_candidate (candidate_id, created_at),
    KEY idx_event (event),
    KEY idx_to_status (to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态变更流水（审计+回溯）';

-- ============== 4. interview（面试安排） ==============
CREATE TABLE IF NOT EXISTS interview (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    slot_start DATETIME NOT NULL COMMENT '面试开始时间',
    duration_minutes INT DEFAULT 90,
    mode VARCHAR(20) DEFAULT 'video' COMMENT 'video / onsite',
    interviewer VARCHAR(50) COMMENT '面试官（教学版固定 interviewer-01）',
    booking_id VARCHAR(50) COMMENT '日历预订号（CalendarTool）',
    questions JSON COMMENT '面试题（InterviewQuestionAgent 产出）',
    feedback TEXT COMMENT '面试官原始反馈',
    status VARCHAR(20) DEFAULT 'BOOKED' COMMENT 'BOOKED / DONE / CANCELLED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_candidate (candidate_id, created_at),
    KEY idx_slot (slot_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试安排';

-- ============== 5. sent_email（邮件发送历史） ==============
CREATE TABLE IF NOT EXISTS sent_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    round_no INT NOT NULL COMMENT '第几轮（0=事务邮件，1=首发邀请，2=追发）',
    from_email VARCHAR(255) NOT NULL,
    to_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    language VARCHAR(10) DEFAULT 'zh',
    smtp_message_id VARCHAR(255) COMMENT '网关响应 ID',
    status VARCHAR(20) COMMENT 'sent / bounced / opened',
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_candidate (candidate_id),
    KEY idx_round (candidate_id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件发送历史';

-- ============== 6. received_email（候选人回信） ==============
CREATE TABLE IF NOT EXISTS received_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT COMMENT '回信匹配到的候选人',
    from_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decision VARCHAR(20) COMMENT '分类结果：confirmed / declined',
    KEY idx_from (from_email),
    KEY idx_candidate (candidate_id),
    KEY idx_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选人回信（生产：IMAP 拉取或 Webhook）';

-- ============== 7. agent_call（Agent 调用埋点） ==============
CREATE TABLE IF NOT EXISTS agent_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    agent_name VARCHAR(50) NOT NULL COMMENT 'Agent 名',
    tools_used JSON COMMENT '调用的工具列表',
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    duration_ms INT,
    status VARCHAR(20) COMMENT 'success / failed / timeout',
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_candidate_agent (candidate_id, agent_name),
    KEY idx_created (created_at),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 调用记录（接 Ch22 ChatModelListener）';

-- ============== 8. audit_log（合规审计） ==============
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor VARCHAR(50) NOT NULL COMMENT 'system / user_id / agent:xxx',
    action VARCHAR(50) NOT NULL COMMENT 'create / read / update / delete / export / decide',
    resource_type VARCHAR(30) NOT NULL COMMENT 'candidate / job / interview / dpo_sample',
    resource_id BIGINT,
    candidate_id BIGINT COMMENT '关联候选人',
    detail JSON,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_actor (actor, created_at),
    KEY idx_candidate (candidate_id, created_at),
    KEY idx_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人事决策合规审计（谁在何时对谁做了什么）';

-- ============== 9. dpo_sample（DPO 对齐样本） ==============
CREATE TABLE IF NOT EXISTS dpo_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt TEXT NOT NULL,
    chosen TEXT NOT NULL COMMENT '人工标注"更好的版本"',
    rejected TEXT NOT NULL COMMENT '人工标注"较差的版本"',
    source VARCHAR(30) COMMENT 'human / bandit / feedback',
    metadata JSON,
    used_in_training TINYINT(1) DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_used (used_in_training, created_at),
    KEY idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DPO 对齐样本（HR 修正 + Bandit 对比）';

-- ============== 10. bandit_state（Bandit 多臂老虎机状态） ==============
CREATE TABLE IF NOT EXISTS bandit_state (
    arm_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '臂 ID（格式：tone:cta:channel）',
    alpha DOUBLE NOT NULL DEFAULT 1.0 COMMENT 'Beta 分布 alpha（成功+1）',
    beta DOUBLE NOT NULL DEFAULT 1.0 COMMENT 'Beta 分布 beta（失败+1）',
    total_pulls BIGINT NOT NULL DEFAULT 0 COMMENT '总拉取次数',
    total_reward DOUBLE NOT NULL DEFAULT 0 COMMENT '累计 reward',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_arm (arm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bandit 状态（Thompson Sampling Beta 分布参数）';

-- ============== 11. bandit_outcome（Bandit 待结算队列） ==============
CREATE TABLE IF NOT EXISTS bandit_outcome (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_id BIGINT NOT NULL,
    arm_id VARCHAR(100) NOT NULL,
    arm_prompt TEXT COMMENT '选择该臂时的 prompt 上下文',
    arm_output TEXT COMMENT '该臂产出的内容（邀请邮件正文）',
    reward DOUBLE COMMENT 'reward 值（0~1；NULL=未结算）',
    outcome_status VARCHAR(20) COMMENT '结算时候选人状态',
    processed_at TIMESTAMP NULL COMMENT 'EvolutionScheduler 结算时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_unsettled (processed_at, created_at),
    KEY idx_candidate (candidate_id),
    KEY idx_arm (arm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bandit 待结算队列（最终 reward 由 EvolutionScheduler 结算）';

-- ============== 索引优化（生产建议） ==============
-- ALTER TABLE candidate ADD INDEX idx_status_job (status, job_id);
-- ALTER TABLE agent_call ADD INDEX idx_agent_created (agent_name, created_at);
-- 这些索引在数据量 > 10万 时才需要，初期不加
