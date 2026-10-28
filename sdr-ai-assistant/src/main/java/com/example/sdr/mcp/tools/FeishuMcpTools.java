package com.example.sdr.mcp.tools;

import com.example.sdr.mcp.mock.FeishuMockClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Ch24 v0.27 MCP：飞书机器人通知（mock）。
 *
 * <p>对内由 SDR 系统主动通知（高意向/销售接管/沉睡盘活等），
 * 对外由外部 LLM Agent 通过本 MCP 触发飞书消息（CLI/IDE 场景）。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public class FeishuMcpTools {

    private final FeishuMockClient client;

    public FeishuMcpTools(FeishuMockClient client) {
        this.client = client;
    }

    @Tool("发飞书通知（mock 写入内存队列）。返回通知 ID")
    public String feishuNotify(@P("webhook URL（教学版仅记录）") String webhookUrl,
                                @P("通知标题") String title,
                                @P("通知正文 Markdown") String content) {
        return client.send(webhookUrl, title, content);
    }
}