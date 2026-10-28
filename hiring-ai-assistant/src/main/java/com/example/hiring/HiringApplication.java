package com.example.hiring;

import com.example.hiring.config.HiringProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SDR AI助手主入口。
 *
 * <h2>项目定位</h2>
 * <p>SDR（Sales Development Rep）= 销售开发代表。
 * 本应用模拟 AI 自动完成 B2B 外贸"找客户 → 发邮件 → 跟进 → 分级"的完整链路。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@link EnableScheduling} —— 72h 监听 / 90 天盘活定时器</li>
 *   <li>{@code @EnableStateMachine} —— 在 {@link com.example.hiring.core.CandidateStateMachineConfig} 上启用 Spring StateMachine（Ch24 FSM 引擎）</li>
 *   <li>{@code @MapperScan} —— MyBatis-Plus mapper 自动扫描</li>
 * </ul>
 *
 * <h2>技术栈（详见 ARCHITECTURE.md）</h2>
 * <ul>
 *   <li>Spring Boot 3.5.13 + JDK 21</li>
 *   <li>LangChain4j 1.19.0-beta29（AI Agent 编排）</li>
 *   <li>Spring StateMachine 4.x（业务状态流转，Redis 持久化）</li>
 *   <li>MyBatis-Plus 3.5.9 + MySQL 8.0（业务数据 7 张表）</li>
 *   <li>Spring Data Redis 7.x（运行时 KV）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@EnableScheduling
@MapperScan("com.example.hiring.mapper")
@EnableConfigurationProperties(HiringProperties.class)
@SpringBootApplication
public class HiringApplication {

    public static void main(String[] args) {
        SpringApplication.run(HiringApplication.class, args);
    }
}