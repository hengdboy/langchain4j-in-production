package com.example.assistant.observability;

import lombok.Getter;

import java.util.Map;

/**
 * 可观测性：模型单价表（每百万 token，¥ 或 $）。
 *
 * <p>简化版教学数据——生产应从厂商 API 实时同步，或用 LiteLLM 等统一接口。
 *
 * <h2>数据来源</h2>
 * <ul>
 * <li>DeepSeek-V3：input ¥1/M、output ¥2/M（官方定价 2025-08）</li>
 * <li>DashScope qwen3.7-flash：input ¥0.003/M、output ¥0.006/M（国内通义）</li>
 * <li>OpenAI gpt-4o-mini：input $0.15/M、output $0.60/M（海外对照）</li>
 * </ul>
 *
 * <p> 价格随时变动——本项目价格仅供演示，请到厂商官网核对最新定价。
 *
 * @author langchain4j-tutorial
 * @since v0.19
 */
@Getter
public enum ModelPricing {

    DEEPSEEK_V3("deepseek-chat", 1.0, 2.0, "CNY"),
    QWEN_FLASH("qwen3.7-flash-2026-07-15", 0.003, 0.006, "CNY"),
    GPT_4O_MINI("gpt-4o-mini", 0.15, 0.60, "USD"),
    DEEPSEEK_V4_FLASH("deepseek-v4-flash", 1.0, 2.0, "CNY"),
    // 2026-10-28 实测：请求 model-name=deepseek-v4-flash，DeepSeek 服务端在响应的
    // model 字段里归一化回传 "deepseek-flash"——listener 读的是响应名，别名不进表
    // 成本就永远是 0（Ch22 踩坑清单 #10）
    DEEPSEEK_V4_FLASH_ALIAS("deepseek-flash", 1.0, 2.0, "CNY"),
    UNKNOWN("unknown", 0.0, 0.0, "CNY");

    private final String modelKey;
    private final double inputPerMTokens;   // ¥/$ per 1M tokens
    private final double outputPerMTokens;
    private final String currency;

    private static final Map<String, ModelPricing> INDEX = Map.of(
            DEEPSEEK_V3.modelKey, DEEPSEEK_V3,
            QWEN_FLASH.modelKey, QWEN_FLASH,
            GPT_4O_MINI.modelKey, GPT_4O_MINI,
            DEEPSEEK_V4_FLASH.modelKey, DEEPSEEK_V4_FLASH,
            DEEPSEEK_V4_FLASH_ALIAS.modelKey, DEEPSEEK_V4_FLASH_ALIAS
    );

    ModelPricing(String modelKey, double input, double output, String currency) {
        this.modelKey = modelKey;
        this.inputPerMTokens = input;
        this.outputPerMTokens = output;
        this.currency = currency;
    }

    /** 按模型名查找定价；找不到返回 UNKNOWN（费用=0） */
    public static ModelPricing of(String modelName) {
        if (modelName == null) return UNKNOWN;
        return INDEX.getOrDefault(modelName.toLowerCase(), UNKNOWN);
    }
}