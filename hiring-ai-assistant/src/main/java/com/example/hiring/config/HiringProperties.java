package com.example.hiring.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ch24 招聘项目可配置参数（中央配置类）。
 *
 * <h2>为什么需要这个类</h2>
 * <p>招聘 pipeline 里有<strong>大量"魔法时间"</strong>（48h 约面超时、5 天 offer 超时、
 * 1 轮追发、定时器扫描频率……），散落各处用 {@code @Value} 注入会导致测试写大量
 * {@code @TestPropertySource}、生产/教学/测试无法差异化。
 *
 * <h2>配置位置</h2>
 * <p>对应 {@code application.yml} 的 {@code hiring.*} 段（见 ARCHITECTURE.md §2）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Data
@ConfigurationProperties(prefix = "hiring")
public class HiringProperties {

    private Fsm fsm = new Fsm();
    private Scheduler scheduler = new Scheduler();
    private Agent agent = new Agent();
    private Bandit bandit = new Bandit();
    private Mcp mcp = new Mcp();
    private Demo demo = new Demo();

    // ============== FSM 状态机相关时间 ==============
    @Data
    public static class Fsm {
        /**
         * 约面邀请"48 小时无回复"超时（毫秒）。
         * <ul>
         *   <li>教学版：7200000（2 小时，方便观察）</li>
         *   <li>生产：172800000（48 小时）</li>
         * </ul>
         */
        private long replyTimeoutMs = 7_200_000L;  // 2h 默认

        /**
         * offer"5 天无答复"超时（毫秒）。
         * <ul>
         *   <li>教学版：300000（5 分钟）</li>
         *   <li>生产：432000000（5 天）</li>
         * </ul>
         */
        private long offerTimeoutMs = 300_000L;  // 5min 默认

        /**
         * 邀请最大轮次（1 首发 + N-1 追发）。
         * <ul>
         *   <li>招聘业务惯例：2（追发一次即止——招聘岗位不像销售，骚扰候选人伤雇主品牌）</li>
         * </ul>
         */
        private int maxInviteRounds = 2;
    }

    // ============== 定时器扫描频率 ==============
    @Data
    public static class Scheduler {
        /** 延迟事件扫描频率（毫秒）—— 检查 48h/5d 到期 */
        private long eventScanIntervalMs = 60_000L;  // 1 min

        /** Bandit 评估周期（毫秒）—— 结算 bandit_outcome 队列 */
        private long banditEvalIntervalMs = 600_000L;  // 10 min
    }

    // ============== Agent 处理相关 ==============
    @Data
    public static class Agent {
        /** 单个 Agent 调用超时（毫秒） */
        private long callTimeoutMs = 30_000L;  // 30s

        /** Agent 重试次数（失败后） */
        private int maxRetries = 2;

        /** Agent 重试间隔（毫秒） */
        private long retryIntervalMs = 2_000L;  // 2s
    }

    // ============== Bandit 多臂老虎机 ==============
    @Data
    public static class Bandit {
        /** 算法：thompson-sampling / ucb1 / epsilon-greedy */
        private String strategy = "thompson-sampling";

        /** 探索因子（0~1，越大越爱探索） */
        private double explorationFactor = 0.1;

        /** DPO 样本数 ≥ 此阈值才重训 */
        private int retrainThreshold = 100;
    }

    // ============== MCP Server ==============
    @Data
    public static class Mcp {
        /** 是否启用 stdio MCP Server */
        private boolean serverEnabled = true;

        /** HTTP MCP Server 端口（未来扩展） */
        private int httpPort = 8092;
    }

    // ============== Demo ==============
    @Data
    public static class Demo {
        /** demo 是否真实调 Agent（false → canned 评估，无 key 环境可跑） */
        private boolean useRealAgent = true;
    }

    // ============== 便利方法 ==============

    /** 48h 约面超时转 Duration */
    public Duration replyTimeout() {
        return Duration.ofMillis(fsm.replyTimeoutMs);
    }

    /** 5 天 offer 超时转 Duration */
    public Duration offerTimeout() {
        return Duration.ofMillis(fsm.offerTimeoutMs);
    }
}
