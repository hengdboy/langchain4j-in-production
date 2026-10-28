-- ============================================================
-- SDR AI 助手 - 数据库 Schema（ARCHITECTURE.md §4）
-- 7 张业务表
--
-- 初始化方式：
--   docker exec -i sdr-mysql mysql -usdr -psdr_pass sdrdb < schema.sql
--
-- 或通过 docker-entrypoint-initdb.d 自动初始化（schema.sql 已挂载）
-- ============================================================

-- ============== 1. lead（销售线索主表） ==============
-- ⚠️ lead 是 MySQL 保留字（LEAD() 窗口函数），必须用反引号包裹
CREATE TABLE IF NOT EXISTS `lead` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_email VARCHAR(255) NOT NULL,
    company_name VARCHAR(255),
    country_code CHAR(2) COMMENT 'ISO 3166-1 alpha-2',
    status VARCHAR(20) NOT NULL COMMENT 'FSM 状态：RAW/QUALIFIED/READY/STEP1_SENT/INTENT_X/POOL/BOUNCE/DORMANT/HANDOFF',
    key_person VARCHAR(100) COMMENT '决策人 KP',
    key_person_email VARCHAR(255),
    smtp_verified TINYINT(1) DEFAULT 0 COMMENT 'SMTP 验真结果',
    matched_product_ids JSON COMMENT 'DNA 知识库匹配的产品 ID 列表',
    selected_language VARCHAR(10) COMMENT '开发信语种：zh/en/ar/...',
    sent_rounds INT DEFAULT 0 COMMENT '已发送轮次（最多 3）',
    intent_grade VARCHAR(20) COMMENT '意向分级：A/B/C/D',
    last_sent_at TIMESTAMP NULL COMMENT '最近一次邮件发送时间',
    extras JSON COMMENT 'Agent 共享临时数据',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_email (customer_email),
    KEY idx_status (status),
    KEY idx_country (country_code),
    KEY idx_intent (intent_grade),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售线索主表';

-- ============== 2. lead_event（状态变更流水） ==============
CREATE TABLE IF NOT EXISTS lead_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    from_status VARCHAR(20) COMMENT '变更前状态',
    to_status VARCHAR(20) NOT NULL COMMENT '变更后状态',
    event VARCHAR(30) NOT NULL COMMENT '触发事件',
    actor VARCHAR(50) COMMENT 'system / agent:xxx / human:xxx',
    reason TEXT,
    metadata JSON COMMENT 'LeadContext.extras 快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead (lead_id, created_at),
    KEY idx_event (event),
    KEY idx_to_status (to_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态变更流水（审计+回溯）';

-- ============== 3. agent_call（Agent 调用埋点） ==============
CREATE TABLE IF NOT EXISTS agent_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    agent_name VARCHAR(50) NOT NULL COMMENT 'Agent 名',
    tools_used JSON COMMENT '调用的工具列表',
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    duration_ms INT,
    status VARCHAR(20) COMMENT 'success / failed / timeout',
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead_agent (lead_id, agent_name),
    KEY idx_created (created_at),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 调用记录（接 Ch22 ChatModelListener）';

-- ============== 4. sent_email（邮件发送历史） ==============
CREATE TABLE IF NOT EXISTS sent_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    round_no INT NOT NULL COMMENT '第几轮（1-3）',
    from_email VARCHAR(255) NOT NULL,
    to_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    language VARCHAR(10),
    smtp_message_id VARCHAR(255) COMMENT 'SMTP 响应 ID',
    status VARCHAR(20) COMMENT 'sent / bounced / opened',
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead (lead_id),
    KEY idx_round (lead_id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件发送历史';

-- ============== 5. received_email（客户回信） ==============
CREATE TABLE IF NOT EXISTS received_email (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT COMMENT 'SMTP 解析后匹配到的 lead',
    from_email VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    body MEDIUMTEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    intent_grade VARCHAR(20) COMMENT '阶段五判定后回填',
    KEY idx_from (from_email),
    KEY idx_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户回信（IMAP/POP3 拉取或 Webhook）';

-- ============== 6. audit_log（GDPR 合规审计） ==============
CREATE TABLE IF NOT EXISTS audit_log (
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
    KEY idx_lead (lead_id, created_at),
    KEY idx_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GDPR 合规审计（接 Ch16 AuditService）';

-- ============== 7. dpo_sample（DPO 对齐样本） ==============
CREATE TABLE IF NOT EXISTS dpo_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt TEXT NOT NULL,
    chosen TEXT NOT NULL COMMENT '人工标注"好的回复"',
    rejected TEXT NOT NULL COMMENT '人工标注"不好的回复"',
    source VARCHAR(30) COMMENT 'human / bandit / feedback',
    metadata JSON,
    used_in_training TINYINT(1) DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_used (used_in_training, created_at),
    KEY idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DPO 对齐样本（阶段六自迭代用）';

-- ============== 索引优化（生产建议） ==============
-- ALTER TABLE lead ADD INDEX idx_status_country (status, country_code);
-- ALTER TABLE agent_call ADD INDEX idx_agent_created (agent_name, created_at);
-- 这些索引在数据量 > 10万 时才需要，初期不加

-- ============== 8. bandit_state（Bandit 多臂老虎机状态） ==============
CREATE TABLE IF NOT EXISTS bandit_state (
    arm_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '臂 ID（格式：tone:cta:channel）',
    alpha DOUBLE NOT NULL DEFAULT 1.0 COMMENT 'Beta 分布 alpha（成功+1）',
    beta DOUBLE NOT NULL DEFAULT 1.0 COMMENT 'Beta 分布 beta（失败+1）',
    total_pulls BIGINT NOT NULL DEFAULT 0 COMMENT '总拉取次数',
    total_reward DOUBLE NOT NULL DEFAULT 0 COMMENT '累计 reward',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_arm (arm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bandit 状态（Thompson Sampling Beta 分布参数）';

-- ============== 9. bandit_outcome（Bandit 待结算队列） ==============
CREATE TABLE IF NOT EXISTS bandit_outcome (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    arm_id VARCHAR(100) NOT NULL,
    arm_prompt TEXT COMMENT '选择该臂时的 prompt 上下文',
    arm_output TEXT COMMENT '该臂产出的内容',
    reward DOUBLE COMMENT 'reward 值（0~1；NULL=未结算）',
    outcome_status VARCHAR(20) COMMENT 'INTENT_A/B/C/D/HANDOFF/POOL/DORMANT',
    processed_at TIMESTAMP NULL COMMENT 'EvolutionScheduler 结算时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_unsettled (processed_at, created_at),
    KEY idx_lead (lead_id),
    KEY idx_arm (arm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bandit 待结算队列（每条 arm 选择记录，最终 reward 由 EvolutionScheduler 结算）';