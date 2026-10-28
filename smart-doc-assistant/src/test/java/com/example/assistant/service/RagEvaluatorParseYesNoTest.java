package com.example.assistant.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / ：RagEvaluator.parseYesNo 单元测试。
 *
 * <p>背景（ 修复记录）：旧实现用 {@code contains("是")}，「这是编造」也会被判忠实。
 * 现实现只认「是 / 否 / 不是」打头，其余返回 null（按未判定处理）。
 *
 * @author langchain4j-tutorial
 * @since v0.19
 */
@DisplayName("parseYesNo：判官回复解析")
class RagEvaluatorParseYesNoTest {

    @Test
    @DisplayName("「是」打头 → true（哪怕后面跟着别的字）")
    void yesPrefix() {
        assertThat(RagEvaluator.parseYesNo("是")).isTrue();
        assertThat(RagEvaluator.parseYesNo("是的，回答完全基于资料")).isTrue();
    }

    @Test
    @DisplayName("「否 / 不是」打头 → false")
    void noPrefix() {
        assertThat(RagEvaluator.parseYesNo("否")).isFalse();
        assertThat(RagEvaluator.parseYesNo("不是，回答包含编造内容")).isFalse();
    }

    @Test
    @DisplayName("含「是」但不是打头 → null（旧实现的漏判场景）")
    void embeddedYesIsNotParsed() {
        assertThat(RagEvaluator.parseYesNo("这是编造")).isNull();
        assertThat(RagEvaluator.parseYesNo("回答是否忠实：是")).isNull();
    }

    @Test
    @DisplayName("空 / null / 无法解析 → null（按未判定处理，不算通过）")
    void unparseable() {
        assertThat(RagEvaluator.parseYesNo(null)).isNull();
        assertThat(RagEvaluator.parseYesNo("  ")).isNull();
        assertThat(RagEvaluator.parseYesNo("无法判断")).isNull();
    }
}
