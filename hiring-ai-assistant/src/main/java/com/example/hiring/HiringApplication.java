package com.example.hiring;

import com.example.hiring.config.HiringProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 招聘 AI 助手主入口。
 *
 * <p>覆盖候选人全流程：简历解析 → 决策脱敏 → 岗位匹配 → 约面邀请 →
 * 排期出题 → 评价汇总 → offer 跟踪。业务流转由 Spring StateMachine 驱动，
 * Agent 只在叶子节点产出结构化结果。
 *
 * <p>架构契约与表结构见 ARCHITECTURE.md。
 * @author hengdboy
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