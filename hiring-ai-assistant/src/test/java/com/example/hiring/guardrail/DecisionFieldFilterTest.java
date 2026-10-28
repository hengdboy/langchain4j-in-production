package com.example.hiring.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策白名单过滤器单元测试——歧视防护的第一道闸。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
class DecisionFieldFilterTest {

    private final DecisionFieldFilter filter = new DecisionFieldFilter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("【白名单】技能/年限/学历/项目放行，姓名/联系方式/受保护特征全拦")
    void testWhitelist() throws Exception {
        String full = """
                {"name":"陈静","email":"cj@example.com","phone":"13800002222",
                 "birthDate":"1998-03-12","age":28,"gender":"女","maritalStatus":"已婚",
                 "hukou":"杭州","ethnicity":"汉","photo":"证件照",
                 "yearsOfExperience":4,"skills":["Java","Spring","RAG"],
                 "education":"本科","projects":["知识库问答"],"notes":"到岗快"}
                """;
        String redacted = filter.redact(full);

        assertThat(filter.hasKey(redacted, "yearsOfExperience")).isTrue();
        assertThat(filter.hasKey(redacted, "skills")).isTrue();
        assertThat(filter.hasKey(redacted, "education")).isTrue();
        assertThat(filter.hasKey(redacted, "projects")).isTrue();

        for (String banned : new String[]{"name", "email", "phone", "birthDate", "age",
                "gender", "maritalStatus", "hukou", "ethnicity", "photo"}) {
            assertThat(filter.hasKey(redacted, banned)).as("banned=" + banned).isFalse();
        }
        assertThat(redacted).doesNotContain("陈静").doesNotContain("已婚").doesNotContain("杭州");
        log.info("白名单过滤：{}", redacted);
    }

    @Test
    @DisplayName("【值级兜底】白名单字段的文本里混入'28 岁'也被抹掉")
    void testValueLevelRedaction() {
        String sneaky = "{\"notes\":\"沟通良好，28 岁，期望到岗快\"}";
        String redacted = filter.redact(sneaky);
        assertThat(redacted).doesNotContain("28 岁").contains("[REDACTED]");
    }

    @Test
    @DisplayName("【值级兜底2】解析器把'性别：女/已婚/户籍：杭州'塞进 notes 也被洗掉（2026-10-29 实测泄漏案例）")
    void testProtectedTermsInNotesValue() {
        // 真实 LLM 解析 c4 简历时产出的 notes 原文（值级清洗前）
        String leaky = "{\"notes\":\"性别：女；年龄：[REDACTED]；已婚；户籍：杭州\",\"skills\":[\"Java\"]}";
        String redacted = filter.redact(leaky);
        assertThat(redacted).doesNotContain("女").doesNotContain("已婚")
                .doesNotContain("户籍").doesNotContain("杭州");
        assertThat(filter.hasKey(redacted, "skills")).isTrue();
        log.info("notes 值清洗后：{}", redacted);
    }

    @Test
    @DisplayName("【非法输入】不是 JSON 时原样返回（不抛异常阻断业务）")
    void testNonJsonPassthrough() {
        assertThat(filter.redact("纯文本画像")).isEqualTo("纯文本画像");
        assertThat(filter.redact("")).isEqualTo("{}");
        assertThat(filter.redact(null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("【空数组】skills 数组结构保留")
    void testArrayKept() throws Exception {
        String redacted = filter.redact("{\"skills\":[\"Java\",\"RAG\"],\"gender\":\"女\"}");
        assertThat(objectMapper.readTree(redacted).get("skills").isArray()).isTrue();
        assertThat(filter.hasKey(redacted, "gender")).isFalse();
    }
}
