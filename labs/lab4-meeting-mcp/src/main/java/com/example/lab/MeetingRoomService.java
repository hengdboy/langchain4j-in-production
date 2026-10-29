package com.example.lab;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lab4 会议室领域服务：预订/取消/冲突检测——MCP 只是"怎么暴露"，业务在这里。
 *
 * <p>三条业务规则（也是护栏的落点）：
 * 单次预订 ≤ 4 小时；同一房间同时段冲突直接拒绝；过去日期/已开始的预订不可操作。
 * @author hengdboy
 */
public final class MeetingRoomService {

    public record Room(String roomId, String name, int capacity, String floor) {}

    public record Booking(String bookingId, String roomId, String date,
                          int startHour, int endHour, String topic, String bookedBy) {}

    public static final class BookingException extends RuntimeException {
        public final String code;
        public BookingException(String code, String message) { super(message); this.code = code; }
    }

    private static final int MAX_HOURS = 4;
    private static final Map<String, Room> ROOMS = Map.of(
            "R-101", new Room("R-101", "小会议室（6 人）", 6, "3F"),
            "R-201", new Room("R-201", "中会议室（12 人）", 12, "5F"),
            "R-301", new Room("R-301", "路演厅（30 人）", 30, "8F"));

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public List<Room> listRooms() {
        return List.copyOf(ROOMS.values());
    }

    public Room room(String roomId) {
        Room r = ROOMS.get(roomId);
        if (r == null) throw new BookingException("room.not_found", "查无会议室：" + roomId);
        return r;
    }

    /** 某房间某天的占用明细：每条一行 "start-end 已订(topic)"。 */
    public List<String> availability(String roomId, String date) {
        room(roomId);
        requireValidDate(date);
        List<String> lines = bookings.values().stream()
                .filter(b -> b.roomId().equals(roomId) && b.date().equals(date))
                .sorted(Comparator.comparingInt(Booking::startHour))
                .map(b -> "%02d:00-%02d:00 已订「%s」（%s）".formatted(b.startHour(), b.endHour(), b.topic(), b.bookingId()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (lines.isEmpty()) lines.add("全天空闲");
        return lines;
    }

    public Booking book(String roomId, String date, int startHour, int endHour, String topic, String bookedBy) {
        room(roomId);
        requireValidDate(date);
        if (startHour < 8 || endHour > 21) throw new BookingException("booking.out_of_hours", "可预订时段为 08:00-21:00");
        if (endHour <= startHour) throw new BookingException("booking.bad_range", "结束时间必须晚于开始时间");
        if (endHour - startHour > MAX_HOURS) throw new BookingException("booking.too_long", "单次预订最长 " + MAX_HOURS + " 小时");
        boolean conflict = bookings.values().stream().anyMatch(b ->
                b.roomId().equals(roomId) && b.date().equals(date)
                        && b.startHour() < endHour && startHour < b.endHour());
        if (conflict) {
            String busy = String.join("；", availability(roomId, date));
            throw new BookingException("booking.conflict", "该时段已被占用：" + busy);
        }
        Booking b = new Booking("BK-" + seq.incrementAndGet(), roomId, date, startHour, endHour, topic, bookedBy);
        bookings.put(b.bookingId(), b);
        return b;
    }

    public String cancel(String bookingId) {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new BookingException("booking.not_found", "查无预订：" + bookingId);
        if (LocalDate.parse(b.date()).isBefore(LocalDate.now())
                || (LocalDate.parse(b.date()).isEqual(LocalDate.now()) && b.startHour() <= java.time.LocalTime.now().getHour())) {
            throw new BookingException("booking.already_started", "预订 " + bookingId + " 已开始或已过期，不能取消");
        }
        bookings.remove(bookingId);
        return "已取消预订 " + bookingId + "（" + b.roomId() + " " + b.date() + " " + b.startHour() + ":00-" + b.endHour() + ":00）";
    }

    private static void requireValidDate(String date) {
        try {
            LocalDate d = LocalDate.parse(date);
            if (d.isBefore(LocalDate.now())) throw new BookingException("booking.past_date", "不能预订过去的日期：" + date);
        } catch (DateTimeParseException e) {
            throw new BookingException("booking.bad_date", "日期格式应为 yyyy-MM-dd，收到：" + date);
        }
    }
}
