package com.example.sdr.mcp;

import com.example.sdr.mcp.tools.CustomsMcpTools;
import com.example.sdr.mcp.tools.EmailMcpTools;
import com.example.sdr.mcp.tools.FeishuMcpTools;
import com.example.sdr.mcp.tools.KpMcpTools;
import com.example.sdr.mcp.tools.SmtpMcpTools;
import com.example.sdr.mcp.mock.FeishuMockClient;
import com.example.sdr.tools.CustomsLookupTool;
import com.example.sdr.tools.KpFinderTool;
import com.example.sdr.tools.MailGateway;
import com.example.sdr.tools.SmtpVerifyTool;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;

import java.util.List;

/**
 * Ch24 v0.27 MCP：{@link McpServer} 构造的工厂方法。
 *
 * <p>统一装配 6 个 MCP Tool（分布在 5 个工具类），避免散落在多处（与 smart-doc Ch19 同模式）。
 *
 * <h2>工具白名单单一真相源</h2>
 * <p>{@link #TOOL_NAMES} 是所有暴露给 MCP 的工具名清单——外部客户端能调用什么由这里唯一决定。
 * 加新工具时只需新建一个 Tools 类 + 加方法 + 在此列表里加名字。
 *
 * @author langchain4j-tutorial
 * @since v0.27
 */
public final class McpServerFactory {

    public static final String SERVER_NAME = "sdr-ai-assistant";
    public static final String SERVER_VERSION = "0.27.0";

    /** 工具白名单（顺序无关） */
    public static final List<String> TOOL_NAMES = List.of(
            "customsLookup",
            "smtpVerify",
            "kpFind",
            "emailSend",
            "emailListSent",
            "feishuNotify"
    );

    private McpServerFactory() {}

    /**
     * 构造带全部 6 个 {@code @Tool} 的 MCP Server。
     *
     * <p>所有依赖（mock 工具 / mappers / mock clients）由调用方注入；MCP Server 进程独立于主
     * Spring Boot 进程，由 {@link McpServerStarter} 装配启动。
     */
    public static McpServer create(CustomsLookupTool customsLookup,
                                   SmtpVerifyTool smtpVerify,
                                   KpFinderTool kpFinder,
                                   MailGateway mailGateway,
                                   FeishuMockClient feishuMockClient) {
        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(SERVER_NAME);
        serverInfo.setVersion(SERVER_VERSION);

        return new McpServer(List.of(
                new CustomsMcpTools(customsLookup),
                new SmtpMcpTools(smtpVerify),
                new KpMcpTools(kpFinder),
                new EmailMcpTools(mailGateway),
                new FeishuMcpTools(feishuMockClient)
        ), serverInfo);
    }
}