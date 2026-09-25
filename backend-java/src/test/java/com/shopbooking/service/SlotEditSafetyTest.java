package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.IntegrationTestBase;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档期编辑安全：老板改档期不能悄悄毁掉顾客的预约——
 * 休息日只关空闲档期、容量不能砍到已约数以下、删档期与缩容都不碰有预约的时段。
 */
class SlotEditSafetyTest extends IntegrationTestBase {

    @Autowired
    private SlotService slotService;
    @Autowired
    private SlotGeneratorService slotGenerator;
    @Autowired
    private BookingService bookingService;

    @Test
    void 整天休息_只关空闲档期_有预约的保留并提示() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 2, 120);
        LocalDate date = futureOpenDate(6);
        TimeSlot freeSlot = createSlot(shop, item, date, LocalTime.of(12, 0), 120, 2);
        TimeSlot bookedSlot = createSlot(shop, item, date, LocalTime.of(18, 0), 120, 2);
        bookingService.hold(bookedSlot.getId(), 4, "rest-day-session", null);

        Map<String, Object> result = slotService.dayClose(shop.getId(), date, true);

        assertEquals(1, ((Number) result.get("changedSlots")).intValue(), "只有空闲档期被关闭");
        assertEquals(1, ((Number) result.get("affectedBookings")).intValue(), "有预约的档期保留并计数提醒");
        assertEquals("CLOSED", slotMapper.selectById(freeSlot.getId()).getStatus());
        assertEquals("OPEN", slotMapper.selectById(bookedSlot.getId()).getStatus(),
                "已有预约的档期原样保留，由老板逐个联系顾客");
    }

    @Test
    void 整天休息_过去与当天日期拒绝操作() {
        Shop shop = createCompletedShop();
        assertThrows(BusinessException.class,
                () -> slotService.dayClose(shop.getId(), LocalDate.now().minusDays(1), true));
        assertThrows(BusinessException.class,
                () -> slotService.dayClose(shop.getId(), LocalDate.now(), true));
    }

    @Test
    void 整天休息可撤销_空闲档期恢复开放() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 2, 120);
        LocalDate date = futureOpenDate(7);
        TimeSlot slot = createSlot(shop, item, date, LocalTime.of(12, 0), 120, 2);

        slotService.dayClose(shop.getId(), date, true);
        assertEquals("CLOSED", slotMapper.selectById(slot.getId()).getStatus());

        Map<String, Object> result = slotService.dayClose(shop.getId(), date, false);
        assertEquals(1, ((Number) result.get("changedSlots")).intValue());
        assertEquals("OPEN", slotMapper.selectById(slot.getId()).getStatus());
    }

    @Test
    void 容量调整_不能低于已约数() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 3, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(1), LocalTime.of(19, 0), 90, 3);
        // booked_count 记录的是预约单数：两个顾客各锁一座
        bookingService.hold(slot.getId(), 2, "capacity-session-a", null);
        bookingService.hold(slot.getId(), 2, "capacity-session-b", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> slotService.updateCapacity(slot.getId(), 1));
        assertTrue(ex.getMessage().contains("已预约数"));

        Map<String, Object> ok = slotService.updateCapacity(slot.getId(), 2);
        assertEquals(2, ((Number) ok.get("capacity")).intValue());
        assertEquals(2, ((Number) ok.get("bookedCount")).intValue());
    }

    @Test
    void 删除未来空闲档期_有预约的时段保留() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 2, 90);
        TimeSlot freeSlot = createSlot(shop, item, futureOpenDate(2), LocalTime.of(12, 0), 90, 2);
        TimeSlot bookedSlot = createSlot(shop, item, futureOpenDate(2), LocalTime.of(18, 0), 90, 2);
        bookingService.hold(bookedSlot.getId(), 2, "delete-safe-session", null);

        int deleted = slotGenerator.deleteFutureFreeSlots(item, LocalDate.now().plusDays(1));

        assertTrue(deleted >= 1);
        assertEquals(null, slotMapper.selectById(freeSlot.getId()), "空闲档期应被删除");
        assertEquals(1, ((Number) slotMapper.selectById(bookedSlot.getId()).getBookedCount()).intValue(),
                "有预约的档期绝不能删");
    }

    @Test
    void 服务缩容_未来空闲档期跟随_有预约的档期不动() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大包间 10 人", 5, 120);
        TimeSlot freeSlot = createSlot(shop, item, futureOpenDate(3), LocalTime.of(12, 0), 120, 5);
        TimeSlot bookedSlot = createSlot(shop, item, futureOpenDate(3), LocalTime.of(18, 0), 120, 5);
        bookingService.hold(bookedSlot.getId(), 2, "shrink-session", null);

        // 老板把 5 个包间改成 3 个
        item.setUnitCount(3);
        slotGenerator.syncFutureCapacity(item);

        assertEquals(3, slotMapper.selectById(freeSlot.getId()).getCapacity(),
                "空闲档期容量应收缩到 3");
        assertEquals(5, slotMapper.selectById(bookedSlot.getId()).getCapacity(),
                "已有预约的档期保持原容量，顾客的座不能砍");
    }

    @Test
    void 月历聚合_休息日与剩余量统计() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "小包间 6 人", 1, 120);
        LocalDate date = futureOpenDate(8);
        createSlot(shop, item, date, LocalTime.of(12, 0), 120, 1);

        java.util.List<Map<String, Object>> calendar =
                slotService.monthCalendar(shop.getId(), date.getYear(), date.getMonthValue());

        Map<String, Object> thatDay = calendar.stream()
                .filter(d -> d.get("date").equals(date.toString()))
                .findFirst().orElseThrow();
        assertEquals(1, ((Number) thatDay.get("total")).intValue());
        assertEquals(1, ((Number) thatDay.get("remaining")).intValue());
        assertEquals(Boolean.FALSE, thatDay.get("restDay"));

        // 没有任何档期的日子不误报为休息日（total=0 时不标 restDay）
        Map<String, Object> anyDay = calendar.get(0);
        if (((Number) anyDay.get("total")).intValue() == 0) {
            assertEquals(Boolean.FALSE, anyDay.get("restDay"));
        }
    }
}
