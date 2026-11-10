package com.example.assistant.engineering;

import com.example.assistant.guardrail.GuardrailMetrics;
import com.example.assistant.guardrail.GuardrailProperties;
import com.example.assistant.guardrail.ToolGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch16：暴露层（TenantToolFilter）与执行层（ToolGuard）共用同一份角色白名单。
 *
 * <p>回归点：修复前 TenantToolFilter 只看 {@code requiredRoles}，而那份元数据全是空集，
 * user 会"看见" calculate，模型必然去调、必然被 ToolGuard 拦——每次白跑一轮还向用户复述拦截文案。
 *
 * @author hengdboy
 */
@DisplayName("TenantToolFilter：暴露层与执行层策略同源")
class TenantToolFilterTest {

    private static final List<ToolCatalog.Entry> CANDIDATES = List.of(
            entry("searchKnowledgeBase"),
            entry("calculate"),
            entry("percentage"),
            entry("currentTime"));

    private GuardrailProperties props() {
        GuardrailProperties props = new GuardrailProperties();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("user", List.of("searchKnowledgeBase"));
        roles.put("admin", List.of());
        roles.put("readonly", List.of("searchKnowledgeBase"));
        props.getTool().setRoleAllowedTools(roles);
        return props;
    }

    private static List<String> names(List<ToolCatalog.Entry> entries) {
        return entries.stream().map(e -> e.spec().name()).toList();
    }

    @Test
    @DisplayName("user 只看得见 searchKnowledgeBase，calculate 不再暴露")
    void userOnlySeesWhitelistedTools() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        assertThat(names(filter.filter(CANDIDATES, "tenantA", "user")))
                .containsExactly("searchKnowledgeBase");
    }

    @Test
    @DisplayName("admin: [] 与执行层一致——不限制，候选工具全可见")
    void emptyAdminListExposesAll() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        assertThat(filter.filter(CANDIDATES, "tenantA", "admin"))
                .containsExactlyElementsOf(CANDIDATES);
    }

    @Test
    @DisplayName("未配置角色 deny by default，暴露层也拦")
    void unknownRoleSeesNothing() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        assertThat(filter.filter(CANDIDATES, "tenantA", "guest")).isEmpty();
    }

    @Test
    @DisplayName("角色大小写不敏感：USER 与 user 暴露集合一致")
    void roleCaseInsensitive() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        assertThat(names(filter.filter(CANDIDATES, "tenantA", "USER")))
                .containsExactly("searchKnowledgeBase");
    }

    @Test
    @DisplayName("工具层整体关闭时不限——暴露全量（与 authorize 放行一致）")
    void guardDisabledExposesAll() {
        GuardrailProperties props = props();
        props.getTool().setEnabled(false);
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        assertThat(filter.filter(CANDIDATES, "tenantA", "user")).hasSize(CANDIDATES.size());
    }

    @Test
    @DisplayName("同源断言：每个候选工具的暴露结果 == ToolGuard 的放行判定")
    void exposureMatchesExecutionDecision() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        for (String role : List.of("user", "admin", "readonly", "guest")) {
            List<ToolCatalog.Entry> exposed = filter.filter(CANDIDATES, "tenantA", role);
            for (ToolCatalog.Entry e : CANDIDATES) {
                assertThat(exposed.contains(e))
                        .as("role=%s tool=%s", role, e.spec().name())
                        .isEqualTo(guard.isToolAllowedForRole(e.spec().name(), role));
            }
        }
    }

    @Test
    @DisplayName("requiredRoles 与白名单取交集：代码级粗闸门仍生效")
    void requiredRolesStillGate() {
        GuardrailProperties props = props();
        ToolGuard guard = new ToolGuard(props, new GuardrailMetrics());
        TenantToolFilter filter = new TenantToolFilter(guard::isToolAllowedForRole);

        ToolCatalog.Entry adminOnly = entry("searchKnowledgeBase", Set.of("admin"));
        assertThat(filter.filter(List.of(adminOnly), "tenantA", "user")).isEmpty();
    }

    private static ToolCatalog.Entry entry(String name) {
        return entry(name, Set.of());
    }

    private static ToolCatalog.Entry entry(String name, Set<String> requiredRoles) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(name)
                .build();
        ToolExecutor executor = (request, memoryId) -> "ok";
        return new ToolCatalog.Entry(spec, executor, "calc", requiredRoles, Set.of());
    }
}
