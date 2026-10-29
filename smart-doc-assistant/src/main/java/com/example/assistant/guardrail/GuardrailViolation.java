package com.example.assistant.guardrail;

/**
 * Guardrails：Guardrail 违反异常。
 *
 * <p>统一异常类型，让 Controller / AiServices 拦截器可以用一个 catch
 * 处理所有 Guardrail 抛出的错误（输入污染 / 输出 PII / API Key 泄露等）。
 * @author hengdboy
 */
public class GuardrailViolation extends RuntimeException {

    /** 异常代码（用于国际化消息 / 监控埋点） */
    private final String code;

    public GuardrailViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
