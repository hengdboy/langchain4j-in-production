package com.example.sdr.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Ch24 阶段四 Mock 邮件网关：模拟 SMTP 发信。
 *
 * <h2>v0.26 角色</h2>
 * <p>由 {@link com.example.sdr.service.LeadPipelineService} 在 READY → EMAIL_SENT
 * 阶段调用，将邮件正文写到内存队列（教学版），不真实发信。
 *
 * <p>生产应替换为真实 SMTP 客户端（MailHog / SendGrid / Amazon SES）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Slf4j
@Component
public class MailGateway {

    private final Deque<SentRecord> mailbox = new ArrayDeque<>();

    /**
     * 发送一封邮件（mock 写入内存队列）。
     *
     * @return messageId（教学版 = "mock-" + sequence）
     */
    public synchronized String send(String to, String subject, String body, String language) {
        String mid = "mock-" + (mailbox.size() + 1);
        SentRecord r = new SentRecord(mid, to, subject, body, language, System.currentTimeMillis());
        mailbox.addFirst(r);
        log.info("📧 [MailGateway] sent messageId={} to={} subject=[{}] lang={} ({} chars)",
                mid, to, subject, language, body == null ? 0 : body.length());
        return mid;
    }

    /** 取最近一封邮件（供测试断言） */
    public SentRecord peekLatest() {
        return mailbox.peekFirst();
    }

    /** 按收件人域名取最近一封（测试用） */
    public SentRecord peekByDomain(String domain) {
        if (domain == null) return null;
        String d = domain.trim().toLowerCase(Locale.ROOT);
        return mailbox.stream()
                .filter(r -> r.to() != null && r.to().toLowerCase(Locale.ROOT).endsWith("@" + d))
                .findFirst()
                .orElse(null);
    }

    /** 取所有已发邮件（只读） */
    public List<SentRecord> all() {
        return Collections.unmodifiableList(new ArrayList<>(mailbox));
    }

    /** 清空（仅测试） */
    public synchronized void clear() {
        mailbox.clear();
        log.warn("🧹 [MailGateway] cleared mailbox");
    }

    /**
     * 已发邮件记录。
     *
     * @param messageId 消息 ID
     * @param to        收件人
     * @param subject   主题
     * @param body      正文
     * @param language  语种
     * @param sentAt    发送时间戳
     */
    public record SentRecord(String messageId, String to, String subject, String body,
                             String language, long sentAt) {}
}