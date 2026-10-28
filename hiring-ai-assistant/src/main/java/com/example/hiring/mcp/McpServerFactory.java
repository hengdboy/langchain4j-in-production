package com.example.hiring.mcp;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import com.example.hiring.mcp.tools.CandidateMcpTools;
import com.example.hiring.mcp.tools.EmailMcpTools;
import com.example.hiring.mcp.tools.InterviewMcpTools;
import com.example.hiring.mcp.tools.JobMcpTools;
import com.example.hiring.tools.MailGateway;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;

import java.util.List;

/**
 * Ch24 v0.30 MCP：{@link McpServer} 构造的工厂方法。
 *
 * <p>统一装配 5 个 MCP Tool（分布在 4 个工具类），避免散落多处（与 Ch19 同模式）。
 *
 * <h2>工具白名单单一真相源</h2>
 * <p>{@link #TOOL_NAMES} 是所有暴露给 MCP 的工具名清单——外部客户端能调用什么由这里唯一决定。
 *
 * @author langchain4j-tutorial
 * @since v0.27 → v0.30
 */
public final class McpServerFactory {

    public static final String SERVER_NAME = "hiring-ai-assistant";
    public static final String SERVER_VERSION = "0.30.0";

    /** 工具白名单（顺序无关） */
    public static final List<String> TOOL_NAMES = List.of(
            "getCandidateStatus",
            "listCandidatesByStatus",
            "listOpenJobs",
            "listInterviews",
            "emailListSent",
            "emailSend"
    );

    private McpServerFactory() {}

    /**
     * 构造带全部 {@code @Tool} 的 MCP Server。
     *
     * <p>所有依赖（内存目录 mock / mock 网关）由调用方注入；MCP Server 进程独立于主
     * Spring Boot 进程，由 {@link McpServerStarter} 装配启动。
     */
    public static McpServer create(HiringDirectoryMock directory, MailGateway mailGateway) {
        McpImplementation serverInfo = new McpImplementation();
        serverInfo.setName(SERVER_NAME);
        serverInfo.setVersion(SERVER_VERSION);

        return new McpServer(List.of(
                new CandidateMcpTools(directory),
                new JobMcpTools(directory),
                new InterviewMcpTools(directory),
                new EmailMcpTools(mailGateway)
        ), serverInfo);
    }
}
