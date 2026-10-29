package com.example.assistant.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 深度开发：MCP 工具调用审计服务（呼应 ）。
 *
 * <p>每次 MCP 客户端通过 {@code tools/call} 调用本服务时，记录：
 * 工具名 / 调用参数（已 redact）/ 耗时 / 是否失败。
 *
 * <p>为什么单独写一个而不复用 {@code AgentAuditService}？
 * <ul>
 * <li>MCP 工具没有会话（{@code memoryId}）概念——MCP 调用天然无状态，
 * 与 Agent 会话模型不同</li>
 * <li>MCP 鉴权身份是 MCP 客户端身份（API Key / OAuth），不是用户 ID；
 * 审计粒度也不同</li>
 * <li>但模式可借鉴：都按"工具 + 时间 + 结果"留痕，方便排查与成本核算</li>
 * </ul>
 *
 * <h2>容量上限（P0-6 修复）</h2>
 * <p>用环形数组实现 {@link #MAX_ENTRIES} 上限：超过容量覆盖最旧记录，避免长时间运行的
 * 进程内存泄漏。{@link #recent(int)} 仍可查询最近 N 条。
 *
 * <h2>敏感数据 redact（P1-5 修复）</h2>
 * <p>{@link #redact(String)} 在写入日志和 audit 前过滤常见敏感字段（password / token /
 * secret / apiKey 等）——避免用户问"我的密码是123456"被原样写到日志里。
 *
 * <p>生产版应把审计写到 ELK / 数据库，本类仅做内存版演示。
 *
 * @author hengdboy
 * @since v0.17
 */
@Slf4j
@Service
public class McpAuditService {

    /** 最大保留审计条数；超过后覆盖最旧记录（环形 buffer）。 */
    private static final int MAX_ENTRIES = 500;

    /** 命中敏感字段 redact 的关键字（不区分大小写）。 */
    private static final String[] SENSITIVE_KEYS = {
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "auth", "credential", "private", "session"
    };

    private final AuditEntry[] ringBuffer = new AuditEntry[MAX_ENTRIES];
    private int writeIndex = 0;
    private int size = 0; // 当前已写入条数（最大 MAX_ENTRIES）

    public synchronized void record(String toolName, String arguments, long durationMs, boolean failed) {
        String safeArgs = redact(arguments);
        AuditEntry e = new AuditEntry(System.currentTimeMillis(), toolName, safeArgs, durationMs, failed);
        ringBuffer[writeIndex] = e;
        writeIndex = (writeIndex + 1) % MAX_ENTRIES;
        if (size < MAX_ENTRIES) size++;
        if (failed) {
            log.warn("[MCP Audit] tool={} failed duration={}ms args={}", toolName, durationMs, safeArgs);
        } else {
            log.info("[MCP Audit] tool={} duration={}ms", toolName, durationMs);
        }
    }

    /**
     * 暴露最近 N 条审计记录（按时间倒序）。
     */
    public synchronized List<AuditEntry> recent(int n) {
        int take = Math.min(n, size);
        List<AuditEntry> out = new ArrayList<>(take);
        // 从最新到最旧遍历
        for (int i = 0; i < take; i++) {
            int idx = (writeIndex - 1 - i + MAX_ENTRIES) % MAX_ENTRIES;
            out.add(ringBuffer[idx]);
        }
        return out;
    }

    /** 审计记录。 */
    public record AuditEntry(long timestamp, String toolName, String arguments, long durationMs, boolean failed) {}

    /**
     * 过滤参数 JSON 里的敏感字段。简单策略：把 {@code "key":"value"} 中匹配
     * {@link #SENSITIVE_KEYS} 的 value 替换为 {@code "***"}。
     *
     * <p>只过滤 {@code "...":"..."} 的字符串值对，不动对象/数组结构；
     * 对真正敏感场景应使用结构化 redact（如 Jackson 树遍历），此处仅做兜底。
     */
    static String redact(String args) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        String out = args;
        for (String key : SENSITIVE_KEYS) {
            // 匹配 "key" 或 "key..." 形式（容忍后缀，如 api_key、apiKey）
            // 用单词边界，但 JSON 的引号特殊
            out = out.replaceAll(
                    "(\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\")([^\"]*)(\")",
                    "$1***$3"
            );
        }
        return out;
    }
}