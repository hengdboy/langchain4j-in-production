package com.example.assistant.engineering;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import com.example.assistant.guardrail.ToolGuard;
import com.example.assistant.guardrail.GuardrailViolation;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ch16 动态工具注入
 *
 * <p>原版用 {@code contains} 关键词硬匹配，存在"同义词漏召回 / 多词并集误召回 / 无租户隔离"三个问题。
 * 本版按生产最佳实践重构为三段式流水线：
 *
 * <pre>
 *   provideTools(request)
 *        │
 *        ├─① 意图路由 IntentRouter  ：用户问题 → 候选业务域（refund/logistics/calc）
 *        │                              （轻量 LLM 分类，关键词兜底，解决漏召回）
 *        │
 *        ├─② 域→工具映射 ToolCatalog ：业务域 → 该域下全部工具 Entry
 *        │
 *        └─③ 租户/角色闸门 TenantToolFilter：按 RequestContext 的 tenantId/role 过滤
 *                                               （安全红线，防越权）
 * </pre>
 *
 * <p>构造期仍扫描每个工具对象的 {@code @Tool} 方法，但额外按其所属业务域登记到 {@link ToolCatalog}，
 * 并为每个工具标注 {@code requiredRoles / enabledTenants}（见下方 DOMAIN_META 注册表）。
 */
@Slf4j
public class DynamicToolProvider implements ToolProvider {

    /** 工具目录：域/角色/租户元数据 + 运行期执行器 */
    private final ToolCatalog catalog = new ToolCatalog();
    private final IntentRouter intentRouter;
    private final TenantToolFilter tenantFilter = new TenantToolFilter();
    private final ToolGuard toolGuard;

    /**
     * chatMemoryId → [tenantId, userId, role]。
     *
     * <p>修复记录（Ch16 16.9）：{@code executeToolsConcurrently()} 把工具执行与回调调度到虚拟线程，
     * {@link RequestContext} 的 ThreadLocal 在那些线程上是默认值——护栏读到默认角色、审计落到
     * default:anonymous。{@code provideTools} 跑在请求线程上、上下文是准的，所以在那一刻把上下文
     * 捕获进本表；工具执行与审计回调按 chatMemoryId 取捕获值。表随会话数增长（教学版可接受，
     * 生产应挂过期或随请求清理）。
     */
    private final java.util.concurrent.ConcurrentHashMap<Object, String[]> requestContexts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 审计/护栏在非请求线程上取上下文的入口（提供者捕获的请求线程上下文快照） */
    public String[] contextFor(Object memoryId) {
        return requestContexts.get(memoryId);
    }

    /**
     * @param tools        工具对象列表（含 @Tool 方法）
     * @param routerModel  用于意图分类的轻量模型（可与主模型不同，生产建议用小模型省成本）
     * @param toolGuard    工具执行期护栏（黑名单/限流/角色白名单），在工具真正执行前拦截
     */
    public DynamicToolProvider(List<Object> tools, ChatModel routerModel, ToolGuard toolGuard) {
        this.intentRouter = new IntentRouter(routerModel);
        this.toolGuard = toolGuard;

        for (Object tool : tools) {
            for (Method m : tool.getClass().getDeclaredMethods()) {
                Tool t = m.getAnnotation(Tool.class);
                if (t == null) continue;

                String name = (t.name() == null || t.name().isBlank()) ? m.getName() : t.name();
                ToolSpecification spec = ToolSpecifications.toolSpecificationFrom(m);
                // propagate=true：工具异常上抛给 AiServices 的 toolExecutionErrorHandler 兜底
                // （与框架 ToolService 自建执行器的选择一致）。裸构造默认 propagate=false，
                // 异常就地转成 isError=true 文本——配在 AiServices 上的错误处理器
                // 对 provider 供给的工具就收不到异常了（Ch16 修复实录）。
                ToolExecutor executor = DefaultToolExecutor.builder()
                        .object(tool)
                        .originalMethod(m)
                        .methodToInvoke(m)
                        .propagateToolExecutionExceptions(true)
                        .build();

                DomainMeta meta = DOMAIN_META.getOrDefault(name, new DomainMeta("calc", Set.of(), Set.of()));
                catalog.register(name, new ToolCatalog.Entry(spec, executor,
                        meta.domain, meta.requiredRoles, meta.enabledTenants));
                log.info("🔧 注册动态工具 [{}] 域={} 角色={} 租户={}", name, meta.domain,
                        meta.requiredRoles, meta.enabledTenants);
            }
        }
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String text = request.userMessage() == null ? "" : request.userMessage().singleText();

        // 本方法跑在请求线程上，ThreadLocal 上下文是准的——在此捕获快照，
        // 供虚拟线程上的工具执行（护栏）与审计回调使用（见 requestContexts 注释）
        String tenantId = RequestContext.tenantId();
        String role = RequestContext.role();
        String userId = RequestContext.userId();
        requestContexts.put(request.chatMemoryId(), new String[]{tenantId, userId, role});

        // ① 意图路由：问题 → 候选业务域
        List<String> domains = intentRouter.route(text);

        // ② 域 → 候选工具
        List<ToolCatalog.Entry> candidates = new ArrayList<>();
        for (String d : domains) {
            candidates.addAll(catalog.byDomain(d));
        }

        // ③ 租户/角色闸门（暴露时筛选）
        List<ToolCatalog.Entry> allowed = tenantFilter.filter(candidates, tenantId, role);

        Map<ToolSpecification, ToolExecutor> selected = new HashMap<>();
        for (ToolCatalog.Entry e : allowed) {
            // 执行期护栏：用代理 executor 包裹真实执行器，
            // 在工具真正执行前先过 ToolGuard（黑名单/限流/角色白名单）。
            // 注意：此处只在「暴露时」按租户/角色筛选（TenantToolFilter），
            // ToolGuard 是「执行时」的兜底拦截——即使模型通过其他方式拿到了工具名，
            // 没有相应权限也会被拦死。授权用的是请求线程捕获的角色，不是执行线程的 ThreadLocal。
            ToolExecutor guarded = (toolExecutionRequest, memoryId) -> {
                String toolName = e.spec().name();
                String[] ctx = memoryId == null ? null : requestContexts.get(memoryId);
                String execRole = ctx != null ? ctx[2] : role;
                String execUser = ctx != null ? ctx[1] : String.valueOf(memoryId);
                try {
                    toolGuard.authorize(toolName, execRole, execUser);
                } catch (GuardrailViolation ex) {
                    log.warn("🚫 工具执行被护栏拦截 [{}] 角色={}：{}", toolName,
                            execRole, ex.getMessage());
                    // 拦截后不执行真实工具，返回提示文本交由模型向用户解释
                    return "⛔ 操作被安全策略拦截：" + ex.getMessage();
                }
                return e.executor().execute(toolExecutionRequest, memoryId);
            };
            selected.put(e.spec(), guarded);
        }

        log.info("🎯 动态工具注入：消息[{}] 租户={} 角色={} 域={} → 暴露 {} 个工具：{}",
                text.length() > 20 ? text.substring(0, 20) + "…" : text,
                tenantId, role, domains,
                selected.size(), selected.keySet().stream().map(ToolSpecification::name).toList());
        return ToolProviderResult.builder().addAll(selected).build();
    }

    /**
     * 工具 → 元数据 注册表。生产应外置到配置/DB，这里用静态 Map 演示。
     * <ul>
     *   <li>searchKnowledgeBase：refund 域，全租户/全角色可用；</li>
     *   <li>currentTime / daysBetween：logistics 域；</li>
     *   <li>calculate / percentage：calc 域；</li>
     * </ul>
     * 若某工具需高权限（如 admin 域的发短信/删数据），在此标 requiredRoles=Set.of("ADMIN")，
     * 非 ADMIN 租户用户即使意图命中也会被 TenantToolFilter 拦掉。
     */
    private static final Map<String, DomainMeta> DOMAIN_META = Map.of(
            "searchKnowledgeBase", new DomainMeta("refund", Set.of(), Set.of()),
            "currentTime", new DomainMeta("logistics", Set.of(), Set.of()),
            "daysBetween", new DomainMeta("logistics", Set.of(), Set.of()),
            "calculate", new DomainMeta("calc", Set.of(), Set.of()),
            "percentage", new DomainMeta("calc", Set.of(), Set.of())
    );

    private record DomainMeta(String domain, Set<String> requiredRoles, Set<String> enabledTenants) {}
}
