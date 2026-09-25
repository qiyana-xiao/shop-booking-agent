package com.shopbooking.service;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁座超时释放：15 分钟未确认即过期，库存回补给其他顾客。
 * 双保险设计：定时扫 + 档期查询前懒清理，两条路径都要验证。
 */
class HoldExpireTest extends IntegrationTestBase {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private SlotService slotService;

    /** 把指定预约单的锁座过期时间改到过去，模拟"超时未确认" */
    private void expireHold(String bookingNo) {
        jdbc.update("UPDATE bookings SET hold_expire_at = ? WHERE booking_no = ?",
                LocalDateTime.now().minusMinutes(1), bookingNo);
    }

    @Test
    void 过期锁座定时扫释放_状态变EXPIRED并回补库存() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 1, 120);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(1), LocalTime.of(18, 0), 120, 1);

        Map<String, Object> held = bookingService.hold(slot.getId(), 4, "timeout-session", null);
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount());

        expireHold((String) held.get("bookingNo"));

        int released = bookingService.releaseExpiredHolds();
        assertEquals(1, released, "应释放 1 条过期锁座");
        assertEquals(0, slotMapper.selectById(slot.getId()).getBookedCount(), "库存必须回补");
        String status = jdbc.queryForObject(
                "SELECT status FROM bookings WHERE booking_no = ?", String.class, held.get("bookingNo"));
        assertEquals("EXPIRED", status);
    }

    @Test
    void 过期后原顾客确认失败_提示重新查档期() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 1, 120);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(2), LocalTime.of(18, 0), 120, 1);

        Map<String, Object> held = bookingService.hold(slot.getId(), 2, "timeout-confirm", null);
        expireHold((String) held.get("bookingNo"));
        bookingService.releaseExpiredHolds();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                bookingService.confirm((String) held.get("bookingNo"), "张三", "13800001111",
                        null, "timeout-confirm", null, null));
        assertTrue(ex.getMessage().contains("已失效"));
    }

    @Test
    void 过期释放后其他顾客可立即预约同一时段() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 1, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(3), LocalTime.of(19, 0), 90, 1);

        Map<String, Object> first = bookingService.hold(slot.getId(), 4, "slow-session", null);
        expireHold((String) first.get("bookingNo"));
        bookingService.releaseExpiredHolds();

        Map<String, Object> second = bookingService.hold(slot.getId(), 3, "fast-session", null);
        assertEquals("HELD", second.get("status"));
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount(), "慢顾客的锁座已让位");
    }

    @Test
    void 档期查询触发懒清理_余量实时可见() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 2, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(4), LocalTime.of(19, 0), 90, 2);

        bookingService.hold(slot.getId(), 4, "lazy-session", null);
        assertEquals(1, slotService.checkAvailability(shop.getId(), slot.getSlotDate(), null, null, item.getId())
                .get("total"));
        expireHold(jdbc.queryForObject(
                "SELECT booking_no FROM bookings WHERE customer_session_id = 'lazy-session'",
                String.class));

        // 不直接调 releaseExpiredHolds，靠 checkAvailability 内部懒清理
        Map<String, Object> availability = slotService.checkAvailability(
                shop.getId(), slot.getSlotDate(), null, null, item.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) availability.get("slots");
        assertEquals(1, slots.size());
        assertEquals(2, slots.get(0).get("remaining"), "过期锁座占的坑必须还给余量");
    }

    @Test
    void 未过期的锁座不被误伤() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 1, 120);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(5), LocalTime.of(18, 0), 120, 1);

        Map<String, Object> held = bookingService.hold(slot.getId(), 4, "fresh-session", null);
        assertEquals(0, bookingService.releaseExpiredHolds(), "未过期锁座不能被释放");
        assertEquals("HELD", jdbc.queryForObject(
                "SELECT status FROM bookings WHERE booking_no = ?", String.class, held.get("bookingNo")));
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount());
        assertNotNull(held.get("holdRemainingSeconds"));
        assertTrue((Long) held.get("holdRemainingSeconds") > 0);
    }
}
