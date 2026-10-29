package com.example.assistant.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性：把 ChatModelListener 接入所有 ChatModel Bean 的配置说明。
 *
 * <p> 本类原来有一个 {@code @Bean} 方法把 {@link ObservabilityChatModelListener} 又包了一层
 * 返回 {@code ChatModelListener}——而监听器类自身已有 {@code @Component}。同一个实例以两个
 * bean 定义存在，starter 会把同类型的 bean 全部收进 ChatModel 的 listener 列表，
 * 结果：<strong>每次调用的 token 被记录两次</strong>（2026-10-28 实测：单次 121 tokens，
 * CostTracker 记 242）。修复：删掉 @Bean 方法，只保留监听器上的 @Component 单通道。
 *
 * <p>教训（Ch22 踩坑清单 #9）：listener 只允许一个注册通道——
 * <ul>
 * <li>要么 {@code @Component} 组件扫描（本项目的最终做法），
 * starter 自动收集容器里所有 {@code ChatModelListener} bean；</li>
 * <li>要么 application.yml 的 {@code listeners: [类全名]} 属性（按类名反射实例化），
 * 但此时类上不能再有 {@code @Component}。</li>
 * </ul>
 * 两条通道同时上 = 每通道一个实例 = 数字翻倍。
 *
 * @author hengdboy
 * @since v0.19
 */
@Slf4j
@Configuration
public class ObservabilityConfig {
}