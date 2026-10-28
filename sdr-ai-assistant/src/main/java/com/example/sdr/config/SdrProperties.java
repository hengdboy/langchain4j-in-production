package com.example.sdr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ch24 SDR 可配置参数（中央配置类）。
 *
 * <h2>为什么需要这个类</h2>
 * <p>SDR 项目里有<strong>大量"魔法时间"</strong>（72h 静默、90天盘活、3 轮追发、定时器扫描频率……），
 * 散落在各处用 {@code @Value} 注入会导致：
 * <ul>
 *   <li>❌ 改一个值要翻多个文件</li>
 *   <li>❌ 测试时要写大量 {@code @TestPropertySource}</li>
 *   <li>❌ 生产 / 教学 / 测试环境无法差异化配置</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>
 * &#64;Service
 * public class FsmTimeoutScheduler {
 *     private final SdrProperties props;
 *     public void schedule(...) {
 *         Duration wait = Duration.ofMillis(props.getFsm().getStepWaitTimeoutMs());
 *     }
 * }
 * </pre>
 *
 * <h2>配置位置</h2>
 * <p>对应 {@code application.yml} 的 {@code sdr.*} 段（见 ARCHITECTURE.md §2）。
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Data
@ConfigurationProperties(prefix = "sdr")
public class SdrProperties {

    private Fsm fsm = new Fsm();
    private Scheduler scheduler = new Scheduler();
    private Agent agent = new Agent();
    private Bandit bandit = new Bandit();
    private Tools tools = new Tools();
    private Mcp mcp = new Mcp();
    private Demo demo = new Demo();

    // ============== FSM 状态机相关时间 ==============
    @Data
    public static class Fsm {
        /**
         * 阶段四"静默期"超时（毫秒）。
         * <ul>
         *   <li>教学版：7200000（2 小时，方便测试）</li>
         *   <li>生产：259200000（72 小时）</li>
         * </ul>
         */
        private long stepWaitTimeoutMs = 72_000_00L;  // 2h 默认

        /**
         * 阶段五"沉睡 90 天盘活"超时（毫秒）。
         * <ul>
         *   <li>教学版：300000（5 分钟）</li>
         *   <li>生产：7_776_000_000L（90 天）</li>
         * </ul>
         */
        private long dormantActivationMs = 300_000L;  // 5min 默认

        /**
         * 阶段四最大追发轮次。
         * <ul>
         *   <li>业务：通常 3 轮（1 轮首发 + 2 轮追发）</li>
         *   <li>高价值客户可调到 5 轮</li>
         * </ul>
         */
        private int maxFollowupRounds = 3;
    }

    // ============== 定时器扫描频率 ==============
    @Data
    public static class Scheduler {
        /** STEP1_SENT 状态扫描频率（毫秒）—— 检查是否 72h 超时 */
        private long step1ScanIntervalMs = 60_000L;  // 1 min

        /** DORMANT 状态扫描频率（毫秒）—— 检查是否 90 天到期 */
        private long dormantScanIntervalMs = 3_600_000L;  // 1 hour

        /** Bandit 评估周期（毫秒）—— 跑 bandit 算法 */
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

    // ============== 工具池大小 ==============
    @Data
    public static class Tools {
        /** SMTP 发信账户池大小 */
        private int smtpPoolSize = 3;

        /** 独立发信域名池大小（防封号） */
        private int domainPoolSize = 5;
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
        /** demo 阶段一是否真实调 Agent（false → canned 评估，无 key 环境可跑） */
        private boolean useRealAgent = true;
    }

    // ============== 便利方法 ==============

    /** 72h 静默期转 Duration */
    public Duration stepWaitTimeout() {
        return Duration.ofMillis(fsm.stepWaitTimeoutMs);
    }

    /** 90天盘活转 Duration */
    public Duration dormantActivation() {
        return Duration.ofMillis(fsm.dormantActivationMs);
    }
}