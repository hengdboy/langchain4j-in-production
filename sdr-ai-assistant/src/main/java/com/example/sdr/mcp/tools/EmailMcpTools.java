package com.example.sdr.mcp.tools;

import com.example.sdr.tools.MailGateway;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Ch24 v0.27 MCP：邮件外发与内存历史。
 *
 * <p>复用 v0.26 的 {@link MailGateway}（mock 邮件网关）——教学版内存队列。
 * 历史记录从 {@link MailGateway#all()} 直接读，无需 DB 依赖。
 *
 * <h2>暴露给 MCP 客户端的 @Tool</h2>
 * <ul>
 *   <li>{@link #emailSend(String, String, String, String)} — 发邮件（mock）</li>
 *   <li>{@link #emailListSent(Integer)} — 取最近 N 条已发邮件（默认 5）</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
@Slf4j
public class EmailMcpTools {

    private final MailGateway mailGateway;

    public EmailMcpTools(MailGateway mailGateway) {
        this.mailGateway = mailGateway;
    }

    @Tool("发邮件（mock 写入 MailGateway 内存队列）。返回 messageId")
    public String emailSend(@P("收件人邮箱") String to,
                            @P("主题") String subject,
                            @P("正文（Markdown）") String body,
                            @P("语种代码，如 en/de/zh") String language) {
        String messageId = mailGateway.send(to, subject, body, language);
        log.info("📧 [EmailMcp] send to={} subject=[{}] lang={} messageId={}", to, subject, language, messageId);
        return messageId;
    }

    @Tool("取最近 N 条已发邮件（默认 5，最多 50）")
    public List<MailGateway.SentRecord> emailListSent(@P("最多返回条数，默认 5") Integer limit) {
        int n = limit == null || limit <= 0 ? 5 : Math.min(limit, 50);
        List<MailGateway.SentRecord> all = mailGateway.all();
        return all.size() <= n ? all : all.subList(0, n);
    }
}