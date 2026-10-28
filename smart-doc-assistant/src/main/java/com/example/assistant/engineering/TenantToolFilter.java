package com.example.assistant.engineering;

import com.example.assistant.engineering.ToolCatalog.Entry;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户 / 角色权限闸门（Tenant & Role Filter）——生产环境"动态工具"的第二层，也是<b>安全红线</b>。
 *
 * <p>无论意图路由选出了哪些候选工具，最终暴露给模型前都必须过这道闸：
 * <ol>
 * <li><b>租户开通</b>：工具 {@code enabledTenants} 为空 = 全租户可用；否则必须包含当前 tenantId；</li>
 * <li><b>角色授权</b>：用户角色必须命中工具 {@code requiredRoles} 之一（空 = 任意角色可用）。</li>
 * </ol>
 *
 * <p>这是多租户 SaaS 防止"租户 A 调到了租户 B/管理员专属工具"的核心手段。
 * 注意：它和意图路由<b>正交</b>——路由决定"应该用哪些"，闸门决定"这个用户能不能用"。
 */
public class TenantToolFilter {

    /**
     * 过滤候选工具列表。
     *
     * @param candidates 意图路由选出的候选 Entry
     * @param tenantId   当前租户（来自 RequestContext）
     * @param role       当前用户角色（来自 RequestContext）
     * @return 通过权限校验、可安全暴露的工具
     */
    public List<Entry> filter(List<Entry> candidates, String tenantId, String role) {
        final String t = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        final String r = (role == null || role.isBlank()) ? "USER" : role;

        return candidates.stream()
                .filter(e -> tenantEnabled(e.enabledTenants(), t))
                .filter(e -> roleAllowed(e.requiredRoles(), r))
                .collect(Collectors.toList());
    }

    private boolean tenantEnabled(Set<String> enabledTenants, String tenantId) {
        return enabledTenants == null || enabledTenants.isEmpty() || enabledTenants.contains(tenantId);
    }

    private boolean roleAllowed(Set<String> requiredRoles, String role) {
        return requiredRoles == null || requiredRoles.isEmpty() || requiredRoles.contains(role);
    }
}
