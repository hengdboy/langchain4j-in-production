package com.example.hiring.mcp.tools;

import com.example.hiring.tools.MailGateway;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * v0.30 MCP 工具：邮件（列出已发/发送）。
 *
 * <p>{@code emailSend} 仅写 mock 网关（教学演示"AI 发信"）；
 * 生产应换成带审批的发信通道——AI 直接对外发信必须有护栏兜底。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public class EmailMcpTools {

    private final MailGateway mailGateway;

    public EmailMcpTools(MailGateway mailGateway) {
        this.mailGateway = mailGateway;
    }

    @Tool(name = "emailListSent", value = "列出最近已发出的邮件（收件人/主题，不含正文）")
    public String emailListSent() {
        var all = mailGateway.all();
        if (all.isEmpty()) {
            return "（暂无已发邮件——主进程与 MCP 子进程的 mock 网关不共享内存，此工具在子进程中只看到 MCP 会话内发送的邮件）";
        }
        StringBuilder sb = new StringBuilder("最近已发邮件：\n");
        all.stream().limit(10).forEach(r ->
                sb.append("- to=%s subject=[%s]\n".formatted(r.to(), r.subject())));
        return sb.toString();
    }

    @Tool(name = "emailSend", value = "发送一封邮件（教学 mock；生产需接审批通道）")
    public String emailSend(@P("收件人邮箱") String to,
                            @P("主题") String subject,
                            @P("正文") String body) {
        String messageId = mailGateway.send(to, subject, body, "zh");
        return "已发送 messageId=" + messageId;
    }
}
