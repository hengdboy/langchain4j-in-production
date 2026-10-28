package com.example.assistant.engineering;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具目录（Tool Catalog）——生产环境"动态工具"的第一层：按业务域 + 权限 + 租户组织工具。
 *
 * <p>替代原来扁平的 {@code Map<name, (spec,executor)>}，每个工具登记元数据：
 * <ul>
 * <li>{@code domain}：业务域（refund/logistics/calc/admin），用于意图路由后的"域→工具"映射；</li>
 * <li>{@code requiredRoles}：调用所需角色，例如 admin 域工具需 ADMIN；</li>
 * <li>{@code enabledTenants}：开通该工具的租户白名单，空集合表示"全租户可用"。</li>
 * </ul>
 *
 * <p>构建方式：在构造器里扫描工具对象的 {@code @Tool} 方法（同原 DynamicToolProvider），
 * 但额外把每个方法的"域/角色/租户"从配套元数据里查出来登记。
 *
 * <p>这样"要暴露哪些工具"就变成：意图路由出候选域 → 按租户/角色过滤，
 * 而不是维护一张爆炸的关键词表。
 */
public class ToolCatalog {

    /** 单条工具登记：含运行时执行器 + 权限元数据 */
    public record Entry(ToolSpecification spec,
                        ToolExecutor executor,
                        String domain,
                        Set<String> requiredRoles,
                        Set<String> enabledTenants) {}

    private final Map<String, Entry> byName = new java.util.HashMap<>();
    private final Map<String, List<Entry>> byDomain = new java.util.HashMap<>();

    /** 注册一个工具方法（由构造函数批量扫描调用） */
    void register(String name, Entry entry) {
        byName.put(name, entry);
        byDomain.computeIfAbsent(entry.domain(), k -> new ArrayList<>()).add(entry);
    }

    public Entry get(String name) {
        return byName.get(name);
    }

    /** 某业务域下的全部工具 */
    public List<Entry> byDomain(String domain) {
        return byDomain.getOrDefault(domain, List.of());
    }

    /** 全部工具名（调试用） */
    public Set<String> allNames() {
        return byName.keySet();
    }
}
