package com.example.hiring.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段三 Mock 日历工具：面试排期占位。
 *
 * <h2>规则（与 lab4 会议预订同源的坑位清单）</h2>
 * <ul>
 * <li>可约时段：未来 5 个工作日的 10:00 / 14:00 / 16:00</li>
 * <li>已被占用的时段不能重复占位（冲突检查）</li>
 * <li>过去时间 / 非工作日拒绝（业务校验前置于一切）</li>
 * <li>最短 1 小时、最长 2 小时（面试时长边界）</li>
 * </ul>
 *
 * <p>生产应替换为 Google Calendar / 飞书日历 API（教学版内存实现，重启即失）。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Component
public class CalendarTool {

    /** 每日固定可约时段 */
    private static final List<LocalTime> DAILY_SLOTS =
            List.of(LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(16, 0));

    /** 已占用时段（教学版单机内存；生产按面试官×时段二维排布） */
    private final Set<LocalDateTime> booked = ConcurrentHashMap.newKeySet();

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private long seq = 0;

    /**
     * 查未来 5 个工作日的空闲时段。
     */
    public List<LocalDateTime> availableSlots() {
        List<LocalDateTime> slots = new ArrayList<>();
        LocalDate d = LocalDate.now().plusDays(1);
        int workdays = 0;
        while (workdays < 5 && slots.size() < 30) {
            if (isWorkday(d)) {
                workdays++;
                for (LocalTime t : DAILY_SLOTS) {
                    LocalDateTime dt = LocalDateTime.of(d, t);
                    if (!booked.contains(dt)) {
                        slots.add(dt);
                    }
                }
            }
            d = d.plusDays(1);
        }
        return slots;
    }

    /**
     * 占用一个时段。
     *
     * @param candidateId 关联候选人（仅作记录，booking 本身可匿名占位）
     * @return 预订确认（bookingId 形如 "BK-20260301-001"）
     * @throws CalendarException 过去时间 / 非工作日 / 已被占用 / 时长越界
     */
    public synchronized Booking book(LocalDateTime start, int durationMinutes, Long candidateId) {
        if (start.isBefore(LocalDateTime.now())) {
            throw new CalendarException("PAST_SLOT", "不能预约过去的时段: " + start);
        }
        if (!isWorkday(start.toLocalDate())) {
            throw new CalendarException("NON_WORKDAY", "只能约工作日: " + start.toLocalDate());
        }
        if (durationMinutes < 60 || durationMinutes > 120) {
            throw new CalendarException("BAD_DURATION", "面试时长须在 60-120 分钟: " + durationMinutes);
        }
        if (booked.contains(start)) {
            throw new CalendarException("SLOT_CONFLICT", "该时段已被占用: " + start);
        }
        booked.add(start);
        String bookingId = "BK-" + start.toLocalDate().toString().replace("-",
                "") + "-" + String.format("%03d", ++seq);
        Booking b = new Booking(bookingId, start, durationMinutes, candidateId);
        bookings.put(bookingId, b);
        log.info("[CalendarTool] booked {} ({}min) → {} candidateId={}",
                start, durationMinutes, bookingId, candidateId);
        return b;
    }

    /** 取消预订（候选人爽约 / 改期）。 */
    public synchronized boolean cancel(String bookingId) {
        Booking b = bookings.remove(bookingId);
        if (b == null) {
            return false;
        }
        booked.remove(b.start());
        log.info("[CalendarTool] cancelled booking {}", bookingId);
        return true;
    }

    /** 按候选人查预订（测试断言用）。 */
    public Booking findByCandidate(Long candidateId) {
        return bookings.values().stream()
                .filter(b -> candidateId.equals(b.candidateId()))
                .findFirst().orElse(null);
    }

    /** 全部预订（只读，测试用）。 */
    public List<Booking> all() {
        return Collections.unmodifiableList(new ArrayList<>(bookings.values()));
    }

    /** 清空（仅测试）。 */
    public synchronized void clear() {
        booked.clear();
        bookings.clear();
    }

    private boolean isWorkday(LocalDate d) {
        var day = d.getDayOfWeek();
        return day.getValue() <= 5;
    }

    /** 预订记录。 */
    public record Booking(String bookingId, LocalDateTime start, int durationMinutes,
                          Long candidateId) {}

    /** 日历业务异常（code 用于 API 层映射 4xx）。 */
    public static class CalendarException extends RuntimeException {
        private final String code;

        public CalendarException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
