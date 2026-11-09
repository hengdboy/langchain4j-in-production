package com.example.assistant.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ch21 / Ch16：ToolGuard 角色矩阵实测（2026-10-28 数据的固化版）。
 *
 * <p>关键语义：整表为空 = 全放行；列了角色 = 白名单逐个匹配，没列的工具一律拒绝；
 * {@code admin: []} 是"零权限"不是"不限制"；<b>角色键大小写不敏感</b>（修复前敏感是 bug，
 * 上下游各层统一用小写，ToolGuard 再加 toLowerCase(Locale.ROOT) 做防御性归一化）。
 *
 * @author hengdboy
 * @since v0.19
 */
@DisplayName("ToolGuard：黑名单 + 角色白名单 + 限流")
class ToolGuardRoleMatrixTest {

    private ToolGuard newGuard() {
        GuardrailProperties props = new GuardrailProperties();
        props.getTool().setBlockedTools(List.of("executeShell", "formatDisk"));
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("user", List.of("searchKnowledgeBase"));
        roles.put("admin", List.of());                     // 空列表 = 零权限
        roles.put("readonly", List.of("searchKnowledgeBase"));
        props.getTool().setRoleAllowedTools(roles);
        props.getTool().setRateLimitPerMinute(30);
        return new ToolGuard(props, new GuardrailMetrics());
    }

    @Test
    @DisplayName("白名单内调用放行（user / readonly 查知识库）")
    void whitelistPasses() {
        ToolGuard g = newGuard();
        assertThatCode(() -> g.authorize("searchKnowledgeBase", "user", "s1")).doesNotThrowAnyException();
        assertThatCode(() -> g.authorize("searchKnowledgeBase", "readonly", "s2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("admin: [] 是零权限——连 searchKnowledgeBase 都被拒")
    void emptyAdminListDeniesEverything() {
        ToolGuard g = newGuard();
        assertThatThrownBy(() -> g.authorize("searchKnowledgeBase", "admin", "s3"))
                .isInstanceOf(GuardrailViolation.class)
                .hasMessageContaining("无权调用");
    }

    @Test
    @DisplayName("黑名单优先于角色（admin 也调不了 executeShell）")
    void blacklistBeatsRole() {
        ToolGuard g = newGuard();
        assertThatThrownBy(() -> g.authorize("executeShell", "admin", "s4"))
                .isInstanceOf(GuardrailViolation.class)
                .hasMessageContaining("已被系统禁用");
    }

    @Test
    @DisplayName("未配置角色 deny by default；角色键大小写不敏感（USER → user 归一化后放行）")
    void unknownRoleAndCaseInsensitive() {
        ToolGuard g = newGuard();
        // guest 不在配置里 → deny by default
        assertThatThrownBy(() -> g.authorize("searchKnowledgeBase", "guest", "s5"))
                .hasMessageContaining("无可用工具权限");
        // USER 大写 → toLowerCase() 归一化成 user → 放行
        assertThatCode(() -> g.authorize("searchKnowledgeBase", "USER", "s6")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("限流 30/min：第 31 次调用触发（实测边界值）")
    void rateLimitTripsAt31stCall() {
        ToolGuard g = newGuard();
        for (int i = 1; i <= 30; i++) {
            g.authorize("searchKnowledgeBase", "user", "rate-session");
        }
        assertThatThrownBy(() -> g.authorize("searchKnowledgeBase", "user", "rate-session"))
                .isInstanceOf(GuardrailViolation.class)
                .hasMessageContaining("调用频率超限");
    }
}
