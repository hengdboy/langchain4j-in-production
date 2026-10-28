package com.example.assistant.mcp;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ch19 MCP 深度开发：Prompt 原语提供者，暴露 3 个预定义 Prompt 模板。
 *
 * <p>MCP Prompt 原语（Ch17.2.1 协议层定义）让服务器预置"标准提问模板"——客户端只填参数，
 * 模板由服务端管理。常见用法：标准客服话术、代码审查模板、安全审计清单等。
 *
 * <h2>与 @SystemMessage 模板的区别</h2>
 * <ul>
 *   <li>{@code @SystemMessage(fromResource=...)}：项目内的 AiService 用，绑死到一个接口方法</li>
 *   <li>MCP Prompts：跨项目共享，任何 MCP 客户端都能用，更适合运营 / 合规统一</li>
 * </ul>
 *
 * <h2>本章预定义的 3 个 Prompt</h2>
 * <ol>
 *   <li>{@code review-code}：代码审查清单（语言 / 重点 / 输出格式）</li>
 *   <li>{@code summarize-doc}：文档摘要（语言 / 最大字数 / 输出风格）</li>
 *   <li>{@code qa-policy-check}：政策问答模板（强制"基于资料回答"）</li>
 * </ol>
 *
 * <h2>模板渲染（P1-3 修复）</h2>
 * <p>{@link #renderTemplate(String, Map)} 实现极简的 {@code {{var}}} 占位符替换：
 * <ul>
 *   <li>占位符内是变量名（字母/数字/下划线）</li>
 *   <li>有 args 提供值 → 替换为 {@code args.get(var).toString()}</li>
 *   <li>无 args 提供值 → 替换为空字符串（防止 LLM 看到原始 {@code {{var}}}）</li>
 * </ul>
 *
 * <p>不支持：Jinja2 的过滤器（{@code {{x | default(...)}}}）、控制结构（{@code if if}）。
 * 够教学用；生产建议用 StringTemplate / Pebble 等正式模板引擎。
 *
 * @author langchain4j-tutorial
 * @since v0.17
 */
@Slf4j
public class McpPromptsProvider {

    /** {{var}} 占位符的正则（变量名：字母/数字/下划线）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /** 启动时一次性构造所有 Prompt 定义。 */
    private final Map<String, PromptDefinition> prompts = new LinkedHashMap<>();

    public McpPromptsProvider() {
        registerCodeReview();
        registerSummarizeDoc();
        registerQaPolicy();
    }

    private void registerCodeReview() {
        List<Map<String, Object>> args = List.of(
                arg("language", "审查目标编程语言（如 Java / Python / Go）", true),
                arg("code", "待审查的代码片段", true),
                arg("focus", "重点关注维度：bug / perf / security / style（可选）", false)
        );
        String template = """
                你是资深 {{language}} 工程师，正在做 Code Review。
                请重点关注：
                1. Bug 风险（边界条件、空指针、并发问题）
                2. 性能瓶颈（算法复杂度、I/O）
                3. 安全漏洞（注入、越权、敏感信息泄露）
                4. 可维护性（命名、注释、复杂度）

                按以下格式输出（无问题就明确说"无问题"）：
                【问题1】
                位置: <行号或函数名>
                类型: Bug / 性能 / 安全 / 可维护
                严重: 高 / 中 / 低
                建议: <具体修改方法>

                代码：
                ```
                {{code}}
                ```
                """;
        prompts.put("review-code", new PromptDefinition("review-code", "代码审查清单模板", args, template));
    }

    private void registerSummarizeDoc() {
        List<Map<String, Object>> args = List.of(
                arg("document", "待摘要的文档全文", true),
                arg("max_words", "摘要最大字数（默认 200）", false),
                arg("language", "输出语言（默认 中文）", false)
        );
        // 默认值采用 fallback：args 缺该 key 时回退到字面字符串
        String template = """
                请对以下文档做摘要：
                - 不超过 {{max_words}} 字
                - 保留关键数据和结论
                - 用 {{language}} 输出

                文档内容：
                {{document}}
                """;
        prompts.put("summarize-doc", new PromptDefinition("summarize-doc", "文档摘要模板", args, template));
    }

    private void registerQaPolicy() {
        List<Map<String, Object>> args = List.of(
                arg("question", "用户的问题", true),
                arg("context", "检索到的参考资料（多片段拼接）", true)
        );
        String template = """
                你是智能客服助手。严格依据下方【参考资料】回答用户问题。
                规则：
                - 资料未提及的内容，明确说"资料中未提及"，绝对不要编造
                - 引用资料时标注片段编号
                - 结尾提示"如需进一步帮助请联系人工客服"

                【参考资料】
                {{context}}

                【用户问题】
                {{question}}

                【回答】
                """;
        prompts.put("qa-policy-check", new PromptDefinition("qa-policy-check",
                "基于资料的政策问答模板（强制不编造）", args, template));
    }

    private static Map<String, Object> arg(String name, String description, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("required", required);
        return m;
    }

    /** 对应 MCP {@code prompts/list} 请求。 */
    public List<Map<String, Object>> listPrompts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromptDefinition p : prompts.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("description", p.description);
            m.put("arguments", p.arguments);
            out.add(m);
        }
        return out;
    }

    /** 对应 MCP {@code prompts/get} 请求。args 中的值会填进模板的 {@code {{var}}}。 */
    public java.util.Optional<List<Map<String, Object>>> getPrompt(String name, Map<String, Object> args) {
        PromptDefinition def = prompts.get(name);
        if (def == null) {
            return java.util.Optional.empty();
        }
        String rendered = renderTemplate(def.template, args);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", Map.of(
                "type", "text",
                "text", rendered
        ));
        return java.util.Optional.of(List.of(message));
    }

    /**
     * 极简模板渲染：把 {@code {{var}}} 替换为 {@code args.get(var).toString()}；
     * args 缺该变量时替换为空字符串，避免 LLM 看到原始占位符（容易被当成真实指令）。
     */
    private static String renderTemplate(String template, Map<String, Object> args) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            Object value = safeArgs.get(varName);
            String replacement = value != null ? value.toString() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private record PromptDefinition(String name, String description,
                                    List<Map<String, Object>> arguments,
                                    String template) {}
}