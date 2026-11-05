package com.example.hiring.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 歧视防护第一步：<b>决策白名单过滤器</b>（确定性组件，不是 LLM 护栏）。
 *
 * <h2>为什么是白名单不是黑名单</h2>
 * <p>黑名单（"把年龄删掉"）永远列不全——简历字段千奇百怪（生日、属相、毕业年份
 * 都能推出年龄）。白名单反过来：<b>只有技能、年限、学历、项目能进评估
 * prompt</b>，其余一律不传。字段根本不存在，比"告诉模型别用"硬得多。
 *
 * <h2>放行的键（决策必需）</h2>
 * <p>yearsOfExperience / skills / education / projects
 *
 * <p><b>2026-11-02 变更：{@code notes} 已移出白名单。</b>它是一个自由文本兜底字段，
 * 解析模型把简历里读到的任何东西都往里塞；键级白名单放行它，等于给受保护特征开了
 * 后门。评估 prompt 只要求四类信息，代码白名单现在和这份契约一致。值级清洗保留，
 * 防的是这四个字段内部混入的漏网文本。
 *
 * <h2>拦掉的键（受保护特征 + 身份信息）</h2>
 * <p>姓名、邮箱、电话、出生日期、年龄、性别、婚育、籍贯、户籍、民族、照片、政治面貌……
 * 以及任何不在白名单里的键。
 *
 * <p>输入不是 JSON 时返回空画像 {@code "{}"} 并告警，不再原样放行——
 * fail-open 等于把解析失败的原始画像直接交给评估，和过滤器存在的理由相反。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Component
public class DecisionFieldFilter {

    /**
     * 决策白名单：只有这些键可以进入评估 prompt
     */
    public static final Set<String> DECISION_ALLOWED = Set.of(
            "yearsOfExperience", "skills", "education", "projects");

    /**
     * 敏感键示例（仅用于测试断言与日志；白名单机制天然覆盖它们）
     */
    public static final List<String> SENSITIVE_KEYS_EXAMPLE = List.of(
            "name", "email", "phone", "birthDate", "age", "gender", "maritalStatus",
            "hukou", "ethnicity", "photo", "politicalStatus");

    /**
     * 值级清洗模式（第二道闸）。
     *
     * <h2>为什么白名单还需要值级清洗（2026-10-29 实测案例）</h2>
     * <p>真实 LLM 解析简历时，会把"性别：女 年龄：28 岁 已婚 户籍：杭州"整段塞进
     * 自由文本字段——键级白名单只认键名，管不了值里藏了什么。所以放行的文本值还要
     * 过一遍受保护特征清洗，与 {@link DiscriminationGuardrail} 的词表同一意图：
     * 结构层（键）+ 内容层（值）双闸。
     *
     * <p>2026-11-02 修第四个模式：原来的 lookbehind 写成了
     * {@code (?<=[：:，,；;、（(【\s]])}，字符类多了一个右方括号，规则自加入起就没命中过；
     * 就算去掉多余的括号，字符串开头的"女，"也没有前导标点可匹配。现在改成否定式
     * lookbehind：串首或标点之后才认，后面必须跟标点或结尾，避免误伤"女士""男方"。
     */
    private static final List<java.util.regex.Pattern> VALUE_SCRUBBERS = List.of(
            // "性别：女 / 年龄：28 岁 / 户籍：杭州" 这类 标签:值 结构
            java.util.regex.Pattern.compile(
                    "(性别|年龄|婚姻状况|婚姻|婚育|籍贯|户籍|民族|政治面貌|出生日期|出生年月)\\s*[：:]?\\s*[^；;，,、\\n]{0,12}"),
            // 独立的婚姻状态词
            java.util.regex.Pattern.compile("已婚已育|已婚|未婚|离异|丧偶"),
            // "男性/女性" 整词
            java.util.regex.Pattern.compile("[男女]性"),
            // 标点分隔或位于串首的独立"男/女"（"女，[REDACTED]..." 是 LLM 丢掉标签
            // 后留下的真实泄漏形态）。前一个字符不是标点、串首除外；后一个字符必须是
            // 标点或结尾，所以"女士""男方"不命中。
            java.util.regex.Pattern.compile(
                    "(?<![^：:，,；;、（(【\\s])[男女](?=[，,；;）)】。\\s]|$)"),
            // 独立的年龄表述
            java.util.regex.Pattern.compile("\\d{1,2}\\s*岁")
    );

    /**
     * 值级清洗的替换标记
     */
    public static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 把完整画像 JSON 过滤成决策画像 JSON。
     *
     * @param profileJson 完整画像（含姓名/联系方式/可能的受保护特征）
     * @return 只含白名单字段的 JSON；输入不可解析时返回 {@code "{}"}
     */
    public String redact(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = objectMapper.readTree(profileJson);
            if (!parsed.isObject()) {
                return "{}";
            }
            ObjectNode root = (ObjectNode) parsed;
            ObjectNode out = objectMapper.createObjectNode();
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (DECISION_ALLOWED.contains(entry.getKey())) {
                    // 对白名单字段的值进行清洗
                    out.set(entry.getKey(), sanitizeValue(entry.getValue()));
                }
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("[DecisionFilter] input is not valid JSON, fail closed: {}",
                    e.getMessage());
            return "{}";
        }
    }

    /**
     * 值级兜底：白名单字段的文本值里混入受保护特征（年龄/性别/婚育/户籍…），抹掉
     */
    private JsonNode sanitizeValue(JsonNode value) {
        if (value.isTextual()) {
            return objectMapper.getNodeFactory().textNode(scrubText(value.asText()));
        }
        if (value.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            value.forEach(v -> out.add(sanitizeValue(v)));
            return out;
        }
        return value;
    }

    /**
     * 文本值清洗：依次套用值级清洗模式，命中段替换为 {@link #REDACTED}。
     */
    public String scrubText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (java.util.regex.Pattern p : VALUE_SCRUBBERS) {
            out = p.matcher(out).replaceAll(REDACTED);
        }
        return out;
    }

    /**
     * 测试辅助：JSON 里是否含指定键（大小写不敏感）。
     */
    public boolean hasKey(String json, String key) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.isObject()) {
                return false;
            }
            var fields = ((ObjectNode) parsed).fields();
            while (fields.hasNext()) {
                if (fields.next().getKey().equalsIgnoreCase(key)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return json != null && json.toLowerCase().contains(key.toLowerCase());
        }
    }
}
