package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.common.SessionKeys;
import com.shopbooking.dto.BookingRequests;
import com.shopbooking.entity.Booking;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.BookingService;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.SlotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 预约操作：顾客本人（sessionKey 或 JWT）可查/改/取消自己的预约；
 * 到店核销与当日全量列表仅限店员/老板（SecurityConfig 拦截，后端为最终边界）。
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final BookingMapper bookingMapper;
    private final SlotService slotService;
    private final ShopService shopService;
    private final AuditService auditService;

    public BookingController(BookingService bookingService, BookingMapper bookingMapper,
                             SlotService slotService,
                             ShopService shopService, AuditService auditService) {
        this.bookingService = bookingService;
        this.bookingMapper = bookingMapper;
        this.slotService = slotService;
        this.shopService = shopService;
        this.auditService = auditService;
    }

    /**
     * 顾客"我的预约"：登录顾客按账号（userId）或当前会话键归属，游客按会话键归属。
     * 不提供按手机号查询——任何人都无法查看或并入他人的预约。
     */
    @GetMapping("/my")
    public List<Map<String, Object>> my(@RequestParam(required = false) String sessionKey,
                                        HttpServletRequest request) {
        String key = SessionKeys.extract(request, sessionKey);
        if (key == null) {
            throw BusinessException.badRequest("缺少会话标识");
        }
        SecurityUser user = currentUser();
        return bookingService.listMyBookings(key, user == null ? null : user.getId());
    }

    @PostMapping("/{bookingNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String bookingNo,
                                      @RequestBody(required = false) BookingRequests.CancelRequest req,
                                      HttpServletRequest request) {
        String sessionKey = SessionKeys.extract(request, req == null ? null : req.sessionKey());
        SecurityUser user = currentUser();
        String reason = req == null ? null : req.reason();
        Map<String, Object> result = bookingService.cancel(bookingNo, reason, sessionKey,
                user == null ? null : user.getId(), user == null ? null : user.getRole());
        return result;
    }

    @PostMapping("/{bookingNo}/reschedule")
    public Map<String, Object> reschedule(@PathVariable String bookingNo,
                                          @RequestBody BookingRequests.RescheduleRequest req,
                                          HttpServletRequest request) {
        if (req == null || req.newSlotId() == null) {
            throw BusinessException.badRequest("请提供要改到的新时段");
        }
        String sessionKey = SessionKeys.extract(request, req.sessionKey());
        SecurityUser user = currentUser();
        return bookingService.reschedule(bookingNo, req.newSlotId(), sessionKey,
                user == null ? null : user.getId(), user == null ? null : user.getRole());
    }

    /** 当日全部预约（店员/老板） */
    @GetMapping("/today")
    public List<Map<String, Object>> today(@RequestParam(required = false) String date) {
        Long shopId = shopService.requirePrimaryShop().getId();
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return slotService.todayViews(shopId, day);
    }

    /** 未来 14 天预约汇总（店员/老板）：打开今日预约页即可看到后面几天还有哪些预约、几单几人 */
    @GetMapping("/upcoming-summary")
    public List<Map<String, Object>> upcomingSummary() {
        Long shopId = shopService.requirePrimaryShop().getId();
        LocalDate today = LocalDate.now();
        return bookingMapper.upcomingSummary(shopId, today, today.plusDays(14));
    }

    /** 到店核销（店员/老板） */
    @PostMapping("/{bookingNo}/checkin")
    public Map<String, Object> checkin(@PathVariable String bookingNo) {
        Map<String, Object> result = bookingService.checkin(bookingNo);
        auditService.audit(currentUser(), "BOOKING_CHECKIN", bookingNo, null);
        return result;
    }

    /** 标记爽约（店员/老板，未到计入爽约统计并释放时段） */
    @PostMapping("/{bookingNo}/no-show")
    public Map<String, Object> noShow(@PathVariable String bookingNo) {
        Map<String, Object> result = bookingService.markNoShow(bookingNo);
        auditService.audit(currentUser(), "BOOKING_NO_SHOW", bookingNo, null);
        return result;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
