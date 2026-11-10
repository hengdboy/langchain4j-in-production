package com.example.assistant.engineering;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态工具注入
 *
 * <p>原版用 {@code contains} 关键词硬匹配，存在"同义词漏召回 / 多词并集误召回 / 无租户隔离"三个问题。
 * 本版按生产最佳实践重构为三段式流水线：
 *
 * <pre>
 * provideTools(request)
 * │
 * ├─意图路由 IntentRouter  ：用户问题 → 候选业务域（refund/logistics/calc）
 * │                              （轻量 LLM 分类，关键词兜底，解决漏召回）
 * │
 * ├─域→工具映射 ToolCatalog ：业务域 → 该域下全部工具 Entry
 * │
 * └─租户/角色闸门 TenantToolFilter：按 RequestContext 的 tenantId/role 过滤
 * （安全红线，防越权）
 * </pre>
 *
 * <p>构造期仍扫描每个工具对象的 {@code @Tool} 方法，但额外按其所属业务域登记到 {@link ToolCatalog}，
 * 并为每个工具标注 {@code requiredRoles / enabledTenants}（见下方 DOMAIN_META 注册表）。
 * @author hengdboy
 */
@Slf4j
public class DynamicToolProvider implements ToolProvider {

    /** 工具目录：域/角色/租户元数据 + 运行期执行器 */
    private final ToolCatalog catalog = new ToolCatalog();
    private final IntentRouter intentRouter;
    private final TenantToolFilter tenantFilter;
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
    private final ConcurrentHashMap<Object, String[]> requestContexts =
            new ConcurrentHashMap<>();

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
        // 暴露层与执行层共用同一份角色白名单（chat.guardrail.tool.role-allowed-tools）：
        // 这里筛掉的角色，模型根本看不见；执行期 ToolGuard 仍保留，作为兜底。
        this.tenantFilter = new TenantToolFilter(toolGuard::isToolAllowedForRole);

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
                        // 参数解析错误必须包成 ToolArgumentsException，AiServices 才会把它
                        // 路由到 toolArgumentsErrorHandler；默认 false 会被当成执行异常。
                        .wrapToolArgumentsExceptions(true)
                        .propagateToolExecutionExceptions(true)
                        .build();

                DomainMeta meta = DOMAIN_META.getOrDefault(name, new DomainMeta("calc", Set.of(), Set.of()));
                catalog.register(name, new ToolCatalog.Entry(spec, executor,
                        meta.domain, meta.requiredRoles, meta.enabledTenants));
                log.info("注册动态工具 [{}] 域={} 角色={} 租户={}", name, meta.domain,
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

        // 意图路由：问题 → 候选业务域
        List<String> domains = intentRouter.route(text);

        // 域 → 候选工具
        List<ToolCatalog.Entry> candidates = new ArrayList<>();
        for (String d : domains) {
            candidates.addAll(catalog.byDomain(d));
        }

        // 租户/角色闸门（暴露时筛选）
        List<ToolCatalog.Entry> allowed = tenantFilter.filter(candidates, tenantId, role);

        // 两种“空”要分开：路由没命中是正常情况；候选被权限闸门全筛掉是另一回事
        if (allowed.isEmpty()) {
            if (candidates.isEmpty()) {
                log.info("动态工具注入：消息[{}] 租户={} 角色={} → 路由未命中任何业务域，本轮不暴露工具",
                        shorten(text), tenantId, role);
            } else {
                log.warn("动态工具注入：消息[{}] 租户={} 角色={} 域={} → 候选 {} 个工具全被租户/角色闸门筛掉：{}",
                        shorten(text), tenantId, role, domains, candidates.size(),
                        candidates.stream().map(e -> e.spec().name()).toList());
            }
            return ToolProviderResult.builder().build();
        }

        if (allowed.size() < candidates.size()) {
            List<String> denied = candidates.stream()
                    .filter(e -> !allowed.contains(e))
                    .map(e -> e.spec().name())
                    .toList();
            log.info("动态工具注入：消息[{}] 租户={} 角色={} → 租户/角色闸门筛掉 {} 个工具：{}",
                    shorten(text), tenantId, role, denied.size(), denied);
        }

        Map<ToolSpecification, ToolExecutor> selected = new HashMap<>();
        for (ToolCatalog.Entry e : allowed) {
            // 执行期护栏：用代理 executor 包裹真实执行器，
            // 在工具真正执行前先过 ToolGuard（黑名单/限流/角色白名单）。
            // ToolGuard 是执行时兜底——即使模型通过其他方式拿到了工具名，
            // 没有相应权限也会被拦死。授权用的是请求线程捕获的角色，不是执行线程的 ThreadLocal。
            selected.put(e.spec(), guardedExecutor(e, role));
        }

        log.info("动态工具注入：消息[{}] 租户={} 角色={} 域={} → 暴露 {} 个工具：{}",
                shorten(text),
                tenantId, role, domains,
                selected.size(), selected.keySet().stream().map(ToolSpecification::name).toList());
        return ToolProviderResult.builder().addAll(selected).build();
    }

    private static String shorten(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "…" : text;
    }

    /**
     * 执行期护栏代理：真正调用工具前过 ToolGuard。
     *
     * <p>拒绝时不再把拦截文案当成"成功的工具结果"返回——那样框架会记 {@code isError=false}，
     * 审计显示成一次成功调用，模型也可能把拒绝当成普通观察值继续自由作答。
     * 这里返回 {@code isError=true} 的结果：模型能分辨这是失败，审计的 {@code hasFailed()} 为 true；
     * 并用 {@code attributes} 带上 {@code blocked/blockReason}（attributes 不发给模型，只供审计/可观测消费）。
     *
     * <p>包级可见是为了让单元测试能直接拿到代理，不必启动完整的 AiService。
     */
    ToolExecutor guardedExecutor(ToolCatalog.Entry entry, String roleAtExposure) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                // 框架同步链路走 executeWithContext；直接调用此方法时保持"显式失败"
                authorize(request.name(), memoryId, roleAtExposure);
                return entry.executor().execute(request, memoryId);
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request,
                                                          InvocationContext context) {
                Object memoryId = context == null ? null : context.chatMemoryId();
                try {
                    authorize(request.name(), memoryId, roleAtExposure);
                } catch (GuardrailViolation ex) {
                    log.warn("工具执行被护栏拦截 [{}] reason={}：{}", request.name(),
                            ex.getCode(), ex.getMessage());
                    return ToolExecutionResult.builder()
                            .isError(true)
                            .resultText("工具调用被拒绝：" + ex.getMessage()
                                    + "。不要编造或推测该工具应返回的数据，请直接告知用户当前无权完成该操作。")
                            .attributes(Map.of("blocked", true, "blockReason", ex.getCode()))
                            .build();
                }
                return entry.executor().executeWithContext(request, context);
            }

            private void authorize(String toolName, Object memoryId, String fallbackRole) {
                String[] ctx = memoryId == null ? null : requestContexts.get(memoryId);
                String capturedRole = (ctx != null ? ctx[2] : fallbackRole);
                String execRole = (capturedRole == null || capturedRole.isBlank())
                        ? "user" : capturedRole.toLowerCase(Locale.ROOT);
                String execUser = ctx != null ? ctx[1] : String.valueOf(memoryId);
                toolGuard.authorize(toolName, execRole, execUser);
            }
        };
    }

    /**
     * 工具 → 元数据 注册表。生产应外置到配置/DB，这里用静态 Map 演示。
     * <ul>
     * <li>searchKnowledgeBase：refund 域，全租户/全角色可用；</li>
     * <li>currentTime / daysBetween：logistics 域；</li>
     * <li>calculate / percentage：calc 域；</li>
     * </ul>
     * 若某工具需高权限（如 admin 域的发短信/删数据），在此标 requiredRoles=Set.of("admin")，
     * 非 admin 租户用户即使意图命中也会被 TenantToolFilter 拦掉。
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
