package com.example.assistant.guardrail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Ch21 Guardrails：Spring 配置——启用 {@link GuardrailProperties} 绑定。
 *
 * <p>配置示例见 {@link GuardrailProperties} JavaDoc。
 * 三个 Guardrail Bean（{@link InputGuard} / {@link OutputGuard} / {@link ToolGuard}）由
 * {@code @Component} 自动注册，这里只负责启用属性绑定。
 *
 * <h2>官方 Guardrail 的 AiServices 集成</h2>
 * <p>本章的 {@link InputGuard} / {@link OutputGuard} 是 <strong>Application 层</strong> 防护；
 * 官方 {@code PatternBasedPromptInjectionGuardrail} / {@code JsonExtractorOutputGuardrail}
 * 是 <strong>AiServices 层</strong> 防护——两者叠加使用，详见 Ch21.3。
 *
 * @author langchain4j-tutorial
 * @since v0.18
 */
@Configuration
@EnableConfigurationProperties(GuardrailProperties.class)
public class GuardrailConfig {
}