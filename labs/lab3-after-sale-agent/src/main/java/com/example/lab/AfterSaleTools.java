package com.example.lab;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lab3 售后工具集（mock 数据）：查订单 / 查退货政策 / 提交退款（带护栏）/ 转人工。
 *
 * <p>金额全部用分。{@code submitRefund} 是写操作——护栏在工具方法体第一行，
 * 超阈值抛 {@link RefundGuard.GuardViolation}，框架会把它转成 isError 结果回喂模型
 * （主教程  失败三层机制的第一层），模型自然会改道转人工。
 */
public class AfterSaleTools {

    /** 订单号 → 详情。mock 数据故意覆盖三类典型：小额 7 天内 / 大额 / 食品类目。 */
    private static final Map<String, Order> ORDERS = new LinkedHashMap<>();

    static {
        ORDERS.put("SO-2026-1001", new Order("SO-2026-1001", "无线机械键盘 K87", 39_900, "外设", LocalDate.now().minusDays(3), "已签收"));
        ORDERS.put("SO-2026-1002", new Order("SO-2026-1002", "65 寸 OLED 电视", 799_900, "大家电", LocalDate.now().minusDays(5), "已签收"));
        ORDERS.put("SO-2026-1003", new Order("SO-2026-1003", "每日坚果 30 包礼盒", 8_900, "食品", LocalDate.now().minusDays(2), "已签收"));
        ORDERS.put("SO-2026-1004", new Order("SO-2026-1004", "降噪耳机 Pro", 129_900, "数码", LocalDate.now().minusDays(40), "已签收"));
    }

    public record Order(String orderId, String productName, long amountFen,
                        String category, LocalDate orderedAt, String status) {}

    @Tool("按订单号查询订单详情：商品名、实付金额（分）、品类、下单日期、订单状态。查不到返回错误说明。")
    public String queryOrder(@P("订单号，如 SO-2026-1001") String orderId) {
        Order o = ORDERS.get(orderId);
        if (o == null) {
            return "查无此订单：" + orderId + "。请与用户核对订单号后重试。";
        }
        return "订单号=%s 商品=%s 实付=%d分(%s元) 品类=%s 下单日=%s 状态=%s"
                .formatted(o.orderId(), o.productName(), o.amountFen(),
                        RefundGuard.fenToYuan(o.amountFen()), o.category(), o.orderedAt(), o.status());
    }

    @Tool("查询某品类商品的退货政策：是否支持 7 天无理由、特殊限制、退款时效。")
    public String checkReturnPolicy(@P("商品品类，如 外设/大家电/食品/数码") String category) {
        return switch (category) {
            case "食品" -> "食品类目：非质量问题不支持退货退款（食品安全法规定）；质量问题凭照片凭证可退，24 小时内响应。";
            case "大家电" -> "大家电：支持 7 天无理由退货，但需商家上门取件，用户承担吊装费；退款在取件验收后 3 个工作日原路退回。";
            case "外设" -> "外设数码类：支持 7 天无理由退货，需配件包装齐全；退款签收后 48 小时内原路退回。";
            default -> "一般商品：支持 7 天无理由退货（自签收次日 0 点起算）；退款签收后 48 小时内原路退回。";
        };
    }

    @Tool("提交退款。仅限 500 元及以内可自动处理；超过 500 元会被系统拦截并要求转人工，不要重试。")
    public String submitRefund(@P("订单号") String orderId,
                              @P("退款金额，单位分") long amountFen,
                              @P("退款原因，简述") String reason) {
        RefundGuard.authorizeAutoRefund(amountFen);   // ← 护栏在写操作的第一行
        Order o = ORDERS.get(orderId);
        if (o == null) return "提交失败：查无此订单 " + orderId;
        if (amountFen > o.amountFen()) return "提交失败：退款金额超过实付金额（实付 " + o.amountFen() + " 分）";
        return "退款已提交：订单 " + orderId + " 退 " + RefundGuard.fenToYuan(amountFen)
                + " 元，原因[" + reason + "]，预计 48 小时内原路退回。";
    }

    @Tool("转人工客服。凡是被退款护栏拦截、政策不明确或用户情绪激动的场景，调用本工具并把工单摘要交给人工。")
    public String requestHuman(@P("订单号") String orderId, @P("转人工原因摘要") String summary) {
        return "已创建人工工单（订单 " + orderId + "）：" + summary + "。人工将在 30 分钟内接入。";
    }
}
