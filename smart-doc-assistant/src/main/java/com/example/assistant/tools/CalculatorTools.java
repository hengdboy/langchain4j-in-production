package com.example.assistant.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ch06 新增：计算器工具
 *
 * 演示：enum 参数 + 多种返回类型
 */
public class CalculatorTools {

    public enum Operation {
        ADD, SUBTRACT, MULTIPLY, DIVIDE
    }

    // 注意：1.19.0 稳定版里 ReturnBehavior.IMMEDIATE_IF_LAST 在工具结果处理阶段会触发
    // NullPointerException（框架构造 ToolExecutionResult 时某字段为 null）。
    // 这里去掉 returnBehavior，使用默认的 TO_LLM：结果先回 LLM 组织语言再返回，行为稳定。
    @Tool(value = "使用高精度计算器执行四则运算。当用户要求『计算』『算一下』时，" +
            "必须使用本工具，不要心算。支持 + - * / 和括号。")
    public double calculate(
            @P("第一个数字") double a,
            @P("运算类型： + ADD  / - SUBTRACT  / * MULTIPLY  / / DIVIDE") Operation op,
            @P("第二个数字") double b
    ) {
        return switch (op) {
            case ADD -> a + b;
            case SUBTRACT -> a - b;
            case MULTIPLY -> a * b;
            case DIVIDE -> {
                if (b == 0) {
                    throw new IllegalArgumentException("除数不能为零，请换个非零的第二个数字");
                }
                yield a / b;
            }
        };
    }

    @Tool("计算部分占总数的百分比，返回 0-100 的数值")
    public double percentage(
            @P("部分值") double part,
            @P("总值") double total
    ) {
        if (total == 0) {
            throw new IllegalArgumentException("总值不能为零");
        }
        return (part / total) * 100;
    }
}