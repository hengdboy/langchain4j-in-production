package com.example.lab;

/**
 * Lab3 退款护栏：金额阈值 + 黑名单——工具层授权的最小可测版本（呼应主教程 Ch21 ToolGuard）。
 *
 * <p>金额一律用「分」（Long）：浮点算钱是工程红线，"99.9 元"这种数在 double 里
 * 根本存不准。阈值 500 元 = 50_000 分，超过即拒绝自动退款、转人工。
 * @author hengdboy
 */
public final class RefundGuard {

    public static final long AUTO_REFUND_LIMIT_FEN = 50_000L;   // 500 元
    private static final java.util.Set<String> BLOCKED_TOOLS = java.util.Set.of("executeShell", "formatDisk", "deleteOrder");

    private RefundGuard() {}

    /** 超阈值抛 {@link GuardViolation}——被 @Tool 方法体调用，异常会被框架转成 isError 结果回喂模型。 */
    public static void authorizeAutoRefund(long amountFen) {
        if (amountFen <= 0) {
            throw new GuardViolation("refund.bad_amount", "退款金额必须为正数（收到：" + amountFen + " 分）");
        }
        if (amountFen > AUTO_REFUND_LIMIT_FEN) {
            throw new GuardViolation("refund.over_limit",
                    "退款 " + fenToYuan(amountFen) + " 元超过自动处理上限 500 元，必须转人工审核，禁止直接提交退款");
        }
    }

    /** 工具黑名单：注入攻击最爱伪装的"运维工具"一律拒绝。 */
    public static void checkToolAllowed(String toolName) {
        if (BLOCKED_TOOLS.contains(toolName)) {
            throw new GuardViolation("tool.blocked", "工具 [" + toolName + "] 已被系统禁用");
        }
    }

    public static String fenToYuan(long fen) {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0);
    }

    /** 与主教程 GuardrailViolation 同构：code + message。 */
    public static final class GuardViolation extends RuntimeException {
        public final String code;
        public GuardViolation(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
