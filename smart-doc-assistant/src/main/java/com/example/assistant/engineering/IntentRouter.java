package com.example.assistant.engineering;

import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 意图路由（Intent Router）——生产环境"动态工具"的第三层：决定本次需要哪些业务域的工具。
 *
 * <p>替换原来 {@code DynamicToolProvider} 里写死的 {@code contains} 关键词匹配，彻底解决
 * "退款规则"这类同义词漏召回、以及多关键词并集误召回的问题。
 *
 * <p>策略（成熟度递进，本实现用方案 B）：
 * <ol>
 * <li><b>LLM 轻量分类</b>：用一个低成本模型，把用户问题分类到已知业务域
 * （refund / logistics / calc / none），输出稳定、可约束；</li>
 * <li><b>关键词兜底</b>：LLM 不可用时（或返回无法解析），回退到关键词，保证不降级为"0 工具导致模型瞎编"。</li>
 * </ol>
 *
 * <p>为什么不直接全量给模型（方案 C）？当工具里包含<b>写操作/高权限/高成本</b>（如 admin 域的发短信、删数据）
 * 时，必须先"分类裁剪 + 二次权限校验"再暴露，避免模型越权调用。纯只读低风险工具可走方案 C。
 */
public class IntentRouter {

    /** 已知业务域（与 ToolCatalog 的 domain 对齐） */
    private static final List<String> KNOWN_DOMAINS = List.of("refund", "logistics", "calc");

    private final ChatModel routerModel;

    public IntentRouter(ChatModel routerModel) {
        this.routerModel = routerModel;
    }

    /** 返回本次应启用的业务域列表（可能为空，但兜底逻辑会尽量给） */
    public List<String> route(String userText) {
        List<String> llmDomains = classifyByLlm(userText);
        if (!llmDomains.isEmpty()) {
            return llmDomains;
        }
        // 兜底：LLM 失败/无法解析 → 关键词（比原版更全，仅作 safety net）
        return fallbackByKeyword(userText);
    }

    private List<String> classifyByLlm(String text) {
        String prompt = """
                你是工具路由分类器。用户问题只能归类到以下业务域之一或多个，用逗号分隔返回域名，
                无关则返回 none。不要解释，不要输出除域名外的任何内容。
                可选域：refund（退款/退货/赔付/政策/规则/知识库咨询）、
                       logistics（物流/发货/时间/时效/配送）、
                       calc（计算/折扣/运费/金额/优惠/百分比）。
                用户问题：%s
                """.formatted(text);

        try {
            String raw = routerModel.chat(prompt)
                    .trim().toLowerCase(Locale.ROOT);
            if (raw.contains("none") && raw.length() <= 4) {
                return List.of();
            }
            return Arrays.stream(raw.split("[,，\\s]+"))
                    .map(String::trim)
                    .filter(KNOWN_DOMAINS::contains)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of(); // 交给兜底
        }
    }

    private List<String> fallbackByKeyword(String text) {
        List<String> domains = new ArrayList<>();
        if (containsAny(text, "知识库", "文档", "资料", "政策", "退款", "规则", "退货", "赔付", "咨询", "条款")) {
            domains.add("refund");
        }
        if (containsAny(text, "时间", "几点", "日期", "发货", "时效", "配送", "什么时候", "物流")) {
            domains.add("logistics");
        }
        if (containsAny(text, "算", "计算", "多少", "折扣", "运费", "优惠", "价格", "金额", "百分比", "折")) {
            domains.add("calc");
        }
        return domains;
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
