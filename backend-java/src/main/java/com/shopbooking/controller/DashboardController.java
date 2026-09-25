package com.shopbooking.controller;

import com.shopbooking.entity.Booking;
import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.service.BookingService;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.SlotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 老板看板（老板/店员）：今日概览、接住率、转化率、未接住 TOP 问题、时段热力图 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final BookingMapper bookingMapper;
    private final SlotService slotService;
    private final ShopService shopService;
    private final BookingService bookingService;

    public DashboardController(BookingMapper bookingMapper, SlotService slotService,
                               ShopService shopService, BookingService bookingService) {
        this.bookingMapper = bookingMapper;
        this.slotService = slotService;
        this.shopService = shopService;
        this.bookingService = bookingService;
    }

    @GetMapping
    public Map<String, Object> dashboard(@RequestParam(required = false) String date) {
        Shop shop = shopService.requirePrimaryShop();
        LocalDate today = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        bookingService.releaseExpiredHolds();

        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();

        long todayConversations = bookingMapper.countConversations(shop.getId(), todayStart, tomorrowStart);
        long todayEscalated = bookingMapper.countEscalatedConversations(shop.getId(), todayStart, tomorrowStart);
        long todayBookings = bookingMapper.countBookingsByStatus(shop.getId(), todayStart, tomorrowStart,
                List.of(Booking.STATUS_CONFIRMED, Booking.STATUS_CHECKED_IN, Booking.STATUS_NO_SHOW));

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> todayOverview = new LinkedHashMap<>();
        todayOverview.put("date", today.toString());
        todayOverview.put("conversations", todayConversations);
        todayOverview.put("aiHandled", Math.max(0, todayConversations - todayEscalated));
        todayOverview.put("catchRate", todayConversations == 0 ? null
                : round((todayConversations - todayEscalated) * 100.0 / todayConversations));
        todayOverview.put("bookings", todayBookings);
        todayOverview.put("escalated", todayEscalated);
        todayOverview.put("missedOrders", 0);
        result.put("today", todayOverview);

        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDateTime monthStartTime = monthStart.atStartOfDay();
        long monthConversations = bookingMapper.countConversations(shop.getId(), monthStartTime, tomorrowStart);
        long monthEscalated = bookingMapper.countEscalatedConversations(shop.getId(), monthStartTime, tomorrowStart);
        long monthBookings = bookingMapper.countBookingsByStatus(shop.getId(), monthStartTime, tomorrowStart,
                List.of(Booking.STATUS_CONFIRMED, Booking.STATUS_CHECKED_IN, Booking.STATUS_NO_SHOW));

        Map<String, Object> monthOverview = new LinkedHashMap<>();
        monthOverview.put("conversations", monthConversations);
        monthOverview.put("aiHandled", Math.max(0, monthConversations - monthEscalated));
        monthOverview.put("catchRate", monthConversations == 0 ? null
                : round((monthConversations - monthEscalated) * 100.0 / monthConversations));
        monthOverview.put("bookings", monthBookings);
        monthOverview.put("conversionRate", monthConversations == 0 ? null
                : round(monthBookings * 100.0 / monthConversations));
        monthOverview.put("escalated", monthEscalated);
        result.put("month", monthOverview);

        List<Map<String, Object>> topReasons = bookingMapper.topEscalationReasons(shop.getId(),
                today.minusDays(30).atStartOfDay());
        result.put("topEscalationReasons", topReasons);

        result.put("heat", heatGrid(shop.getId(), today.minusDays(27), today));
        result.put("todayBookings", slotService.todayViews(shop.getId(), today));
        return result;
    }

    /** 近 28 天时段热力：折叠为 星期(1-7) × 小时(0-23) 网格，前端渲染 7×24 热力图 */
    private Map<String, Object> heatGrid(Long shopId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = bookingMapper.heatBySlotTime(shopId, from, to);
        int[][] grid = new int[7][24];
        int max = 0;
        for (Map<String, Object> row : rows) {
            Object dateObj = row.get("slotDate");
            Object timeObj = row.get("startTime");
            Object cntObj = row.get("cnt");
            if (dateObj == null || timeObj == null || cntObj == null) {
                continue;
            }
            LocalDate d = toLocalDate(dateObj);
            LocalTime t = toLocalTime(timeObj);
            int cnt = ((Number) cntObj).intValue();
            grid[d.getDayOfWeek().getValue() - 1][t.getHour()] += cnt;
            max = Math.max(max, grid[d.getDayOfWeek().getValue() - 1][t.getHour()]);
        }
        List<String> days = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("day", d + 1);
                cell.put("dayName", days.get(d));
                cell.put("hour", h);
                cell.put("count", grid[d][h]);
                cells.add(cell);
            }
        }
        Map<String, Object> heat = new LinkedHashMap<>();
        heat.put("from", from.toString());
        heat.put("to", to.toString());
        heat.put("max", max);
        heat.put("cells", cells);
        return heat;
    }

    private LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.time.LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v));
    }

    private LocalTime toLocalTime(Object v) {
        if (v instanceof LocalTime t) {
            return t;
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        return LocalTime.parse(String.valueOf(v));
    }

    private Object round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
