package com.example.lab;

import java.util.ArrayList;
import java.util.List;

/**
 * Lab2 归一化器：把模型的字符串输出清洗成可比对的强类型记录。
 *
 * <p>结构化抽取的真正难点从来不是"让模型吐 JSON"，而是吐出来的值没法直接入库：
 * "3,500万元" 和 35000000 之间隔着一整套中文数字习惯。本类每个方法都对应
 * 一类真实公告里出现的写法（单元测试逐条钉死）。
 * @author hengdboy
 */
public final class TenderNormalizer {

    public record TenderRecord(String projectName, String purchaserName, Long budgetYuan,
                               String submissionDeadline, List<String> requiredQualifications,
                               Long performanceBondYuan) {}

    private TenderNormalizer() {}

    /** 预算："3,500万元" / "280万" / "12000000元" / "450 万元" → 元（Long）。 */
    public static Long parseBudgetYuan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace(",", "").replace("，", "").replace("人民币", "")
                .replace("元", "").replace("整", "").trim();
        boolean wan = s.contains("万");
        s = s.replace("万", "").trim();
        try {
            long v = Long.parseLong(s);
            return wan ? v * 10_000 : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 截止日期：统一成 ISO yyyy-MM-dd（时分信息丢弃——对账只看日期）。 */
    public static String parseDeadline(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.replace(" ", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})[年\\-/.](\\d{1,2})[月\\-/.](\\d{1,2})").matcher(s);
        if (!m.find()) return null;
        return "%s-%02d-%02d".formatted(m.group(1), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /** 资质列表：去掉序号前缀、按 ；/；再切一刀、丢弃空项。 */
    public static List<String> parseQualifications(List<String> rawList) {
        if (rawList == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : rawList) {
            if (item == null) continue;
            for (String part : item.split("[；;]")) {
                String cleaned = part.trim()
                        .replace(" ", "")
                        .replaceFirst("^\\d+[.、．]\\s*", "")
                        .replaceAll("^(及|和|与)\\s*", "");
                if (!cleaned.isBlank()) out.add(cleaned);
            }
        }
        return out;
    }

    /**
     * 保证金：固定金额 → 元；"不收取" → 0；按比例（"合同金额的 5%"）→ null（无法定值，
     * 调用方按"比例型"单独处理）。
     */
    public static Long parsePerformanceBond(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.contains("不收取") || s.contains("不设") || s.contains("无") || s.contains("采用信用承诺")) return 0L;
        if (s.contains("%") || s.contains("合同金额") || s.contains("合同价")) return null;
        return parseBudgetYuan(s);
    }

    /** 一步到位：原始字段 → 强类型记录。 */
    public static TenderRecord normalize(RawTender raw) {
        return new TenderRecord(
                raw.getProjectName(), raw.getPurchaserName(),
                parseBudgetYuan(raw.getBudgetYuan()),
                parseDeadline(raw.getSubmissionDeadline()),
                parseQualifications(raw.getRequiredQualifications()),
                parsePerformanceBond(raw.getPerformanceBondYuan()));
    }
}
