package com.example.hiring.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策白名单过滤器单元测试——歧视防护的第一道闸。
 *
 * <p>2026-11-02 增补：真实泄漏形态是 {@code notes} 里一句"女，[REDACTED]..."。
 * 原来的用例把"性别：女"当输入，被第一个 标签:值 模式顺手洗掉，第四个独立性别
 * 模式从没被执行过——测试绿着，线上漏了两周。这里的用例改成按真实形态压。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
class DecisionFieldFilterTest {

    private final DecisionFieldFilter filter = new DecisionFieldFilter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("【白名单】技能/年限/学历/项目放行，notes 与身份字段全拦")
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
        assertThat(filter.hasKey(redacted, "notes")).isFalse();

        for (String banned : new String[]{"name", "email", "phone", "birthDate", "age",
                "gender", "maritalStatus", "hukou", "ethnicity", "photo"}) {
            assertThat(filter.hasKey(redacted, banned)).as("banned=" + banned).isFalse();
        }
        assertThat(redacted).doesNotContain("陈静").doesNotContain("已婚").doesNotContain("杭州");
        log.info("白名单过滤：{}", redacted);
    }

    @Test
    @DisplayName("【值级兜底】放行字段的文本里混入'28 岁'也被抹掉")
    void testValueLevelRedaction() {
        String sneaky = "{\"education\":\"本科，28 岁\",\"skills\":[\"Java\"]}";
        String redacted = filter.redact(sneaky);
        assertThat(redacted).doesNotContain("28 岁").contains("[REDACTED]");
        assertThat(redacted).contains("本科");
    }

    @Test
    @DisplayName("【真实泄漏形态】notes 整段不再进决策画像，别处的'女，'也洗得掉")
    void testNotesDroppedAndLeadingGenderScrubbed() {
        // 数据库 #205/#209 的实际形态：标签被模型丢掉，只剩一个以"女，"
        // 开头的 notes；同期解析结果里也可能把同样的文本塞进 projects。
        String leaky = """
                {"notes":"女，[REDACTED]，[REDACTED]，[REDACTED]",
                 "projects":["原文标注：女，知识库问答"],
                 "skills":["Java"]}
                """;
        String redacted = filter.redact(leaky);

        assertThat(filter.hasKey(redacted, "notes")).isFalse();
        assertThat(redacted).doesNotContain("原文标注：女");
        assertThat(redacted).contains("[REDACTED]").contains("知识库问答");
        assertThat(filter.hasKey(redacted, "skills")).isTrue();
        log.info("泄漏形态过滤后：{}", redacted);
    }

    @Test
    @DisplayName("【串首】没有前导标点的'女，'也命中")
    void testGenderAtStartOfString() {
        assertThat(filter.scrubText("女，本科")).isEqualTo("[REDACTED]，本科");
        assertThat(filter.scrubText("女")).isEqualTo("[REDACTED]");
        assertThat(filter.scrubText("男；4 年经验")).isEqualTo("[REDACTED]；4 年经验");
    }

    @Test
    @DisplayName("【不误伤】女士/男方这类正常词原样保留")
    void testNoFalsePositive() {
        String safe = "{\"projects\":[\"负责女士产品线\",\"对接男方家属\"]}";
        assertThat(filter.redact(safe)).contains("女士").contains("男方");
    }

    @Test
    @DisplayName("【非法输入】不是 JSON 时返回空画像（fail-closed，不抛异常）")
    void testNonJsonPassthrough() {
        assertThat(filter.redact("纯文本画像")).isEqualTo("{}");
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
