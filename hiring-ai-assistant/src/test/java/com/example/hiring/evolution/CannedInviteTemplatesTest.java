package com.example.hiring.evolution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ch24 约面邀请模板单元测试（6 臂空间）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@Slf4j
class CannedInviteTemplatesTest {

    @Test
    @DisplayName("【臂空间】6 个臂全部可渲染，占位符替换完整")
    void testAllArms() {
        assertThat(CannedInviteTemplates.ARM_IDS).hasSize(6);
        for (String armId : CannedInviteTemplates.ARM_IDS) {
            CannedInviteTemplates.Template t = CannedInviteTemplates.render(
                    CannedInviteTemplates.forArm(armId), "张伟", "Java 后端", "星航科技");
            assertThat(t.subject()).doesNotContain("{{").doesNotContain("}}");
            assertThat(t.body()).doesNotContain("{{").doesNotContain("}}");
            assertThat(t.body()).contains("张伟").contains("Java 后端").contains("星航科技");
            log.info("✅ arm={} subject=[{}]", armId, t.subject());
        }
    }

    @Test
    @DisplayName("【cta 差异】calendar-link 带链接、reply-slot 引导回复")
    void testCtaDifference() {
        CannedInviteTemplates.Template link = CannedInviteTemplates.render(
                CannedInviteTemplates.forArm("warm:calendar-link:email"), "张伟", "后端", "星航");
        CannedInviteTemplates.Template reply = CannedInviteTemplates.render(
                CannedInviteTemplates.forArm("warm:reply-slot:email"), "张伟", "后端", "星航");
        assertThat(link.body()).contains("https://");
        assertThat(reply.body()).doesNotContain("https://").contains("回复");
    }

    @Test
    @DisplayName("【防御】未知臂抛 IllegalArgumentException")
    void testUnknownArm() {
        assertThatThrownBy(() -> CannedInviteTemplates.forArm("aggressive:bribe:email"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("【空值】占位符缺省不 NPE")
    void testNullPlaceholders() {
        CannedInviteTemplates.Template t = CannedInviteTemplates.render(
                CannedInviteTemplates.forArm("formal:calendar-link:email"),
                null, null, null);
        assertThat(t.body()).contains("候选人");
    }
}
