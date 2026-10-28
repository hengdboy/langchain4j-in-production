package com.example.lab;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.community.mcp.server.transport.StdioMcpServerTransport;

import java.util.List;

/**
 * Lab4 stdio 入口：MCP 客户端（Claude Desktop / Inspector / curl 脚本）拉起的子进程。
 *
 * <p>stdout 只允许出现 JSON-RPC 协议流——这是 stdio 传输的铁律（主教程  踩坑清单 #1）。
 * 本 lab 无任何日志依赖，天然不污染 stdout；接了 Logback 的项目要照  的做法
 * 把日志重定向到 stderr。
 */
public final class McpServerMain {

    private McpServerMain() {}

    public static void main(String[] args) throws InterruptedException {
        McpServer server = new McpServer(java.util.List.of(new MeetingRoomMcpTools()));
        StdioMcpServerTransport transport = new StdioMcpServerTransport(server);
        transport.awaitClose();   // stdin 关闭（客户端退出）时返回，进程随之结束
    }
}
