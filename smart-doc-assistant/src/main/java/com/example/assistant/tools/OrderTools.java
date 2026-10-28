package com.example.assistant.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 新增：订单工具
 * <p>
 * 演示：
 * - getOrder：只读查询工具（无副作用）
 * - createOrder：高风险工具（有副作用，金额超阈值抛审批异常）
 * <p>
 * 注意：LangChain4j 1.x 没有内置 ToolExecutionMode.MANUAL。
 * 高风险操作的审批流需要自己实现——这里用"金额阈值 + 抛自定义异常"演示思路，
 * 实际项目中配合 ToolExecutionErrorHandler 统一处理（详见  6.7 节）。
 */
@Slf4j
public class OrderTools {

    /**
     * 大额订单阈值：超过此金额需要人工审批
     */
    private static final BigDecimal LARGE_ORDER_THRESHOLD = new BigDecimal("1000");

    @Tool("用户给到订单号,查询订单详情，返回订单号、金额、状态和商品列表")
    public Map<String, Object> getOrder(@P("订单ID") String orderId, InvocationParameters parameters) {
        // InvocationParameters 底层是 ConcurrentHashMap，存入的是什么数字类型取出来就是什么。
        // Controller 里 req.userId 是 Long，所以这里必须用 Long 接，不能强转成 Integer，
        // 否则 Long → Integer 会抛 ClassCastException。
        Long userId = parameters.get("userId");
        log.info("用户：{} 查询订单：{}", userId, orderId);
        return Map.of(
                "orderId", orderId,
                "amount", 1280.00,
                "status", "PAID",
                "products", List.of("智能手表", "保护壳")
        );
    }

    @Tool(
            name = "createOrder",
            value = "创建新订单，返回订单号和状态。金额超过 1000 元的大额订单需要特别谨慎"
    )
    public Map<String, Object> createOrder(
            @P("商品名称") String product,
            @P("金额（元）") BigDecimal amount
    ) {
        // 金额超限：抛审批异常（生产环境由 ToolExecutionErrorHandler 统一拦截）
        if (amount.compareTo(LARGE_ORDER_THRESHOLD) > 0) {
            throw new ApprovalRequiredException(
                    "createOrder",
                    "{\"product\":\"" + product + "\",\"amount\":" + amount + "}",
                    "金额 " + amount + " 元超过 " + LARGE_ORDER_THRESHOLD + " 元阈值，需要用户确认"
            );
        }

        String orderId = "ORD-" + System.currentTimeMillis();
        log.info("创建订单：{} {}元 → {}", product, amount, orderId);
        return Map.of(
                "orderId", orderId,
                "product", product,
                "amount", amount,
                "status", "CREATED"
        );
    }

    /**
     * 审批异常：工具需要人工审批时抛出
     * 由 ToolExecutionErrorHandler 捕获并透传给前端
     */
    public static class ApprovalRequiredException extends RuntimeException {
        private final String toolName;
        private final String arguments;
        private final String reason;

        public ApprovalRequiredException(String toolName, String arguments, String reason) {
            super("需要人工审批: " + reason);
            this.toolName = toolName;
            this.arguments = arguments;
            this.reason = reason;
        }

        public String getToolName() {
            return toolName;
        }

        public String getArguments() {
            return arguments;
        }

        public String getReason() {
            return reason;
        }
    }
}
