package com.example.assistant.engineering;

/**
 * 请求级上下文（租户 / 用户 / 角色）——生产环境多租户隔离的载体。
 *
 * <p>为什么用 ThreadLocal：{@code ToolProvider.provideTools(...)} 在 AiServices 工具循环里被同步调用，
 * 而 tenantId/userId/role 是"每次 HTTP 请求"才确定的，无法在 Spring Bean 构造期注入。
 * 用 ThreadLocal 在 Controller 入口写入、在 Provider 里读取，是 Spring 生态（类似 SecurityContextHolder）
 * 传递请求级上下文的标准做法，与 langchain4j 框架内部机制完全解耦。
 *
 * <p> 必须在请求结束时 {@link #clear()}（当前在 EngineeringAgentController 的 finally 块里；
 * 生产建议收口到 HandlerInterceptor 的 afterCompletion），否则线程复用会串租户。
 */
public final class RequestContext {

    private static final ThreadLocal<Context> HOLDER = ThreadLocal.withInitial(Context::new);

    private RequestContext() {}

    /** 每个请求开始：写入租户/用户/角色 */
    public static void set(String tenantId, String userId, String role) {
        Context c = HOLDER.get();
        c.tenantId = tenantId;
        c.userId = userId;
        c.role = (role == null || role.isBlank()) ? "USER" : role;
    }

    public static String tenantId() {
        return HOLDER.get().tenantId;
    }

    public static String userId() {
        return HOLDER.get().userId;
    }

    public static String role() {
        return HOLDER.get().role;
    }

    /** 请求结束：必须清理，防止线程池复用导致租户串号 */
    public static void clear() {
        HOLDER.remove();
    }

    private static final class Context {
        String tenantId = "default";
        String userId = "anonymous";
        String role = "USER";
    }
}
