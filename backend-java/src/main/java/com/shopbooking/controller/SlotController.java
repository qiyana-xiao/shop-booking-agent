package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.Shop;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.BookingService;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.SlotGeneratorService;
import com.shopbooking.service.SlotService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 档期管理（老板/店员）：查询、月历、日明细、整天休息、单时段容量、手动续期 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final SlotService slotService;
    private final SlotGeneratorService slotGenerator;
    private final ShopService shopService;
    private final BookingService bookingService;
    private final AuditService auditService;

    public SlotController(SlotService slotService, SlotGeneratorService slotGenerator,
                          ShopService shopService, BookingService bookingService, AuditService auditService) {
        this.slotService = slotService;
        this.slotGenerator = slotGenerator;
        this.shopService = shopService;
        this.bookingService = bookingService;
        this.auditService = auditService;
    }

    /** 档期查询（与 Agent 的 check_availability 同一实现，保证看到一致的余量） */
    @GetMapping("/availability")
    public Map<String, Object> availability(@RequestParam(required = false) String date,
                                            @RequestParam(required = false) String startTime,
                                            @RequestParam(required = false) Integer partySize,
                                            @RequestParam(required = false) Long serviceItemId) {
        Shop shop = shopService.requirePrimaryShop();
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return slotService.checkAvailability(shop.getId(), day, startTime, partySize, serviceItemId);
    }

    /** 月历聚合：每天 档期数/开放数/已约/剩余/是否休息 */
    @GetMapping("/calendar")
    public Object calendar(@RequestParam(required = false) Integer year,
                           @RequestParam(required = false) Integer month) {
        Long shopId = shopService.requirePrimaryShop().getId();
        java.time.YearMonth ym = year == null || month == null
                ? java.time.YearMonth.now()
                : java.time.YearMonth.of(year, month);
        if (ym.isBefore(java.time.YearMonth.now().minusMonths(12))
                || ym.isAfter(java.time.YearMonth.now().plusMonths(12))) {
            throw BusinessException.badRequest("只能查看前后 12 个月内的档期");
        }
        return slotService.monthCalendar(shopId, ym.getYear(), ym.getMonthValue());
    }

    @GetMapping("/day")
    public Map<String, Object> day(@RequestParam String date) {
        Long shopId = shopService.requirePrimaryShop().getId();
        return slotService.dayDetail(shopId, LocalDate.parse(date));
    }

    /** 整天休息/恢复：改前先弹窗告知影响范围（前端调 preview 接口拿受影响预约数） */
    @PostMapping("/day-close")
    public Map<String, Object> dayClose(@RequestBody Map<String, Object> body) {
        Long shopId = shopService.requirePrimaryShop().getId();
        LocalDate date = LocalDate.parse(String.valueOf(body.get("date")));
        boolean closed = Boolean.parseBoolean(String.valueOf(body.get("closed")));
        Map<String, Object> result = slotService.dayClose(shopId, date, closed);
        auditService.audit(currentUser(), "SLOT_DAY_CLOSE", "date:" + date,
                (closed ? "休息" : "恢复") + "，受影响预约 " + result.get("affectedBookings") + " 条");
        return result;
    }

    /** 休息设置前的预览：返回当天预约数，前端据此弹确认框 */
    @GetMapping("/day-close/preview")
    public Map<String, Object> dayClosePreview(@RequestParam String date) {
        Long shopId = shopService.requirePrimaryShop().getId();
        LocalDate day = LocalDate.parse(date);
        bookingService.releaseExpiredHolds();
        return Map.of("date", day.toString(),
                "affectedBookings", slotService.countDayBookings(shopId, day));
    }

    @PutMapping("/{id}/capacity")
    public Map<String, Object> updateCapacity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int capacity = Integer.parseInt(String.valueOf(body.get("capacity")));
        Map<String, Object> result = slotService.updateCapacity(id, capacity);
        auditService.audit(currentUser(), "SLOT_CAPACITY", "slot:" + id, "容量→" + capacity);
        return result;
    }

    /** 手动触发滚动续期（正常由每日定时任务执行，这里供老板即时补期） */
    @PostMapping("/generate")
    public Map<String, Object> generate() {
        Shop shop = shopService.requirePrimaryShop();
        int before = slotGenerator.defaultDays();
        slotGenerator.rollingRenew();
        auditService.audit(currentUser(), "SLOT_GENERATE", "shop:" + shop.getId(),
                "手动续期 " + before + " 天");
        return Map.of("ok", true, "days", before);
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
