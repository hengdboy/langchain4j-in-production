package com.example.lab;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * Lab4 MCP 工具层：把领域服务包成 4 个 @Tool，由官方社区 SDK 暴露给 MCP 客户端。
 *
 * <p>业务异常（{@link MeetingRoomService.BookingException}）直接往上抛——
 * SDK 的 McpServer 会把它包成 isError:true 的结果返回给客户端（Ch19 踩坑清单 #17 的同款行为）。
 */
public class MeetingRoomMcpTools {

    private final MeetingRoomService service = new MeetingRoomService();

    @Tool("列出所有可预订的会议室：编号、名称、容量、楼层")
    public List<String> queryRooms() {
        return service.listRooms().stream()
                .map(r -> "%s %s 容量%d人 %s".formatted(r.roomId(), r.name(), r.capacity(), r.floor()))
                .toList();
    }

    @Tool("查询某会议室某天的占用情况，返回已订时段或全天空闲")
    public List<String> queryAvailability(@P("会议室编号，如 R-201") String roomId,
                                          @P("日期，格式 yyyy-MM-dd") String date) {
        return service.availability(roomId, date);
    }

    @Tool("预订会议室。规则：时段 08:00-21:00、单次最长 4 小时、冲突直接拒绝。返回预订号。")
    public String bookRoom(@P("会议室编号") String roomId,
                           @P("日期 yyyy-MM-dd") String date,
                           @P("开始小时（8-20 的整数）") int startHour,
                           @P("结束小时（9-21 的整数）") int endHour,
                           @P("会议主题") String topic,
                           @P("预订人姓名") String bookedBy) {
        MeetingRoomService.Booking b = service.book(roomId, date, startHour, endHour, topic, bookedBy);
        return "预订成功：%s（%s）%s %02d:00-%02d:00 主题[%s] 预订号 %s"
                .formatted(b.roomId(), service.room(b.roomId()).name(), b.date(),
                        b.startHour(), b.endHour(), b.topic(), b.bookingId());
    }

    @Tool("取消预订。已开始或已过期的预订不能取消。")
    public String cancelBooking(@P("预订号，如 BK-1001") String bookingId) {
        return service.cancel(bookingId);
    }
}
