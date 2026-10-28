package com.example.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.mcp.server.McpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab4 离线测试：直接驱动官方 SDK 的 {@code McpServer.handle(JsonNode)}——
 * 不起子进程、不接 Claude Desktop，JSON-RPC 协议行为全部可断言。
 */
@DisplayName("Lab4 会议室 MCP Server：协议 + 业务护栏")
class Lab4McpServerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final McpServer server = new McpServer(java.util.List.of(new MeetingRoomMcpTools()));

    /** SDK 的 handle 返回 McpJsonRpcMessage（子类带 result），用 Jackson 转成树来断言。 */
    private JsonNode call(String json) throws Exception {
        Object msg = server.handle(M.readTree(json));
        return msg == null ? null : M.readTree(M.writeValueAsString(msg));
    }

    @Test
    @DisplayName("initialize 握手：协议版本与服务器名")
    void initialize() throws Exception {
        JsonNode resp = call(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(resp.get("result").get("protocolVersion").asText()).isNotBlank();
    }

    @Test
    @DisplayName("tools/list：恰好 4 个工具，名字与 @Tool 方法一致")
    void toolsList() throws Exception {
        JsonNode resp = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(resp.get("result").get("tools").toString())
                .contains("queryRooms", "queryAvailability", "bookRoom", "cancelBooking");
    }

    @Test
    @DisplayName("预订成功 → 冲突被拒（isError）→ 同房间另一时段成功")
    void bookConflictReflow() throws Exception {
        String date = LocalDate.now().plusDays(3).toString();
        JsonNode ok = call(toolCall(3, "bookRoom",
                "{\"roomId\":\"R-201\",\"date\":\"" + date + "\",\"startHour\":10,\"endHour\":12,\"topic\":\"周会\",\"bookedBy\":\"张三\"}"));
        assertThat(ok.get("result").get("content").get(0).get("text").asText()).contains("预订成功");

        JsonNode conflict = call(toolCall(4, "bookRoom",
                "{\"roomId\":\"R-201\",\"date\":\"" + date + "\",\"startHour\":11,\"endHour\":13,\"topic\":\"复盘\",\"bookedBy\":\"李四\"}"));
        assertThat(conflict.get("result").get("isError").asBoolean()).isTrue();
        assertThat(conflict.get("result").get("content").get(0).get("text").asText()).contains("已被占用");

        JsonNode other = call(toolCall(5, "bookRoom",
                "{\"roomId\":\"R-201\",\"date\":\"" + date + "\",\"startHour\":14,\"endHour\":16,\"topic\":\"复盘\",\"bookedBy\":\"李四\"}"));
        assertThat(other.get("result").get("content").get(0).get("text").asText()).contains("预订成功");
    }

    @Test
    @DisplayName("业务护栏：超 4 小时 / 时段越界 / 过去日期 全部 isError")
    void businessGuards() throws Exception {
        String date = LocalDate.now().plusDays(1).toString();
        assertThat(call(toolCall(6, "bookRoom",
                "{\"roomId\":\"R-101\",\"date\":\"" + date + "\",\"startHour\":9,\"endHour\":14,\"topic\":\"集训\",\"bookedBy\":\"王五\"}"))
                .get("result").get("content").get(0).get("text").asText()).contains("最长 4 小时");
        assertThat(call(toolCall(7, "bookRoom",
                "{\"roomId\":\"R-101\",\"date\":\"" + date + "\",\"startHour\":7,\"endHour\":9,\"topic\":\"早会\",\"bookedBy\":\"王五\"}"))
                .get("result").get("content").get(0).get("text").asText()).contains("08:00-21:00");
        assertThat(call(toolCall(8, "bookRoom",
                "{\"roomId\":\"R-101\",\"date\":\"2020-01-01\",\"startHour\":9,\"endHour\":10,\"topic\":\"x\",\"bookedBy\":\"王五\"}"))
                .get("result").get("content").get(0).get("text").asText()).contains("过去");
    }

    @Test
    @DisplayName("Ch19 坑点 16 在 lab 里复现：字符串 id → handle 返回 null（200 空响应的根源）")
    void stringIdSilentlyDropped() throws Exception {
        JsonNode resp = call("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"tools/list\",\"params\":{}}");
        assertThat(resp).isNull();
    }

    private static String toolCall(int id, String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
    }
}
