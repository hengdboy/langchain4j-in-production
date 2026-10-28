package com.example.sdr.evolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ch24 CannedContentTemplates 单元测试。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Slf4j
@DisplayName("CannedContentTemplates 单元测试")
class CannedContentTemplatesTest {

    @Test
    @DisplayName("【ARM_IDS】6 个臂（3 tone × 2 cta × 1 channel）")
    void testArmIds() {
        assertThat(CannedContentTemplates.ARM_IDS).hasSize(6);
        assertThat(CannedContentTemplates.ARM_IDS).contains(
                "formal:quote:email", "casual:quote:email", "concise:quote:email",
                "formal:sample:email", "casual:sample:email", "concise:sample:email");
        log.info("✅ 6 臂：{}", CannedContentTemplates.ARM_IDS);
    }

    @Test
    @DisplayName("【渲染】占位符 {{company}} / {{kp}} / {{imports}} 被替换")
    void testRender() {
        CannedContentTemplates.Template t = CannedContentTemplates.forArm("casual:quote:email");
        CannedContentTemplates.Template r = CannedContentTemplates.render(t, "ACME", "Sarah", "smart home");
        assertThat(r.subject()).contains("ACME");
        assertThat(r.subject()).doesNotContain("{{company}}");
        assertThat(r.body()).contains("Sarah");
        assertThat(r.body()).contains("smart home");
        log.info("✅ 渲染：subject=[{}] bodyChars={}", r.subject(), r.body().length());
    }

    @Test
    @DisplayName("【未知 arm】抛 IllegalArgumentException")
    void testUnknownArm() {
        assertThatThrownBy(() -> CannedContentTemplates.forArm("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown arm");
        log.info("✅ 未知 arm 抛异常");
    }

    @Test
    @DisplayName("【不同 tone】casual 包含 emoji；formal 无 emoji")
    void testToneDistinction() {
        var casual = CannedContentTemplates.forArm("casual:quote:email");
        var formal = CannedContentTemplates.forArm("formal:quote:email");
        assertThat(casual.subject()).contains("👋");
        assertThat(formal.subject()).doesNotContain("👋");
        assertThat(formal.body()).contains("Dear ");
        log.info("✅ tone 区分：casual 含 emoji, formal 正式称呼");
    }
}