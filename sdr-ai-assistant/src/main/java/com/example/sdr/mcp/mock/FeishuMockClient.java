package com.example.sdr.mcp.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Ch24 阶段五 Mock：飞书 Webhook 通知客户端（v0.27 MCP 暴露）。
 *
 * <h2>v0.27 角色</h2>
 * <p>{@link com.example.sdr.mcp.tools.FeishuMcpTools} 通过本客户端向"飞书机器人"
 * 发通知（高意向 / 销售接管 / 沉睡盘活等）。生产应替换为真实飞书开放平台 webhook；
 * 教学版用内存队列收集通知，断言时可遍历。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
@Component
public class FeishuMockClient {

    private final Deque<Notification> inbox = new ArrayDeque<>();

    /**
     * 发送飞书通知。
     *
     * @param webhookUrl 飞书机器人 webhook URL（教学版仅记录，不实际调用）
     * @param title       通知标题
     * @param content 正文（Markdown）
     * @return 通知 ID
     */
    public synchronized String send(String webhookUrl, String title, String content) {
        String id = "feishu-" + UUID.randomUUID().toString().substring(0, 8);
        Notification n = new Notification(id, webhookUrl, title, content, System.currentTimeMillis());
        inbox.addFirst(n);
        log.info("📣 [FeishuMock] sent id={} title=[{}] ({} chars) → {}",
                id, title, content == null ? 0 : content.length(), webhookUrl);
        return id;
    }

    /** 取最近一条通知 */
    public Notification peekLatest() {
        return inbox.peekFirst();
    }

    /** 全部通知（只读） */
    public List<Notification> all() {
        return Collections.unmodifiableList(new ArrayList<>(inbox));
    }

    /** 清空（仅测试） */
    public synchronized void clear() {
        inbox.clear();
        log.warn("🧹 [FeishuMock] cleared inbox");
    }

    /**
     * 飞书通知记录。
     */
    public record Notification(String id, String webhookUrl, String title, String content, long sentAt) {}
}