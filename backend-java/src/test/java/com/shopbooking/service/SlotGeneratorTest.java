package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.IntegrationTestBase;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档期生成器：营业规则展开成具体时段、休息日跳过、重复生成幂等、
 * 滚动补期保证未来档期永远存在。
 */
class SlotGeneratorTest extends IntegrationTestBase {

    @Autowired
    private SlotGeneratorService slotGenerator;

    /** 09:00-21:00、粒度 60、时长 120：可开始时间 09:00~19:00，共 11 个时段 */
    private static final int SLOTS_PER_DAY_120MIN = 11;

    @Test
    void 规则展开_跳过休息日_时长不越界() {
        Shop shop = createCompletedShop(); // 周一休息，其余 09:00-21:00，粒度 60
        ServiceItem item = createItem(shop, "小包间 6 人", 3, 120);

        LocalDate from = LocalDate.now();
        int days = 7;
        int created = slotGenerator.generateForShop(shop, List.of(item), from, days);

        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .between(TimeSlot::getSlotDate, from, from.plusDays(days - 1))
                .orderByAsc(TimeSlot::getSlotDate).orderByAsc(TimeSlot::getStartTime));

        int mondays = 0;
        int openDays = 0;
        LocalDate currentDate = null;
        int perDay = 0;
        for (TimeSlot s : slots) {
            if (s.getSlotDate().getDayOfWeek().getValue() == 1) {
                mondays++;
            }
            if (!s.getSlotDate().equals(currentDate)) {
                if (currentDate != null && perDay != SLOTS_PER_DAY_120MIN) {
                    throw new AssertionError(currentDate + " 应有 " + SLOTS_PER_DAY_120MIN + " 个时段，实际 " + perDay);
                }
                currentDate = s.getSlotDate();
                perDay = 0;
                openDays++;
            }
            perDay++;
            // 时段不能越过打烊时间
            assertTrue(!s.getEndTime().isAfter(java.time.LocalTime.of(21, 0)),
                    "时段越界：" + s.getStartTime() + "-" + s.getEndTime());
            assertEquals(3, s.getCapacity(), "容量必须等于服务项数量");
            assertEquals(0, s.getBookedCount());
            assertEquals("OPEN", s.getStatus());
        }
        assertEquals(SLOTS_PER_DAY_120MIN, perDay, currentDate + " 每个营业日都应有 11 个时段");
        assertEquals(created, slots.size());
        assertEquals(0, mondays, "周一休息日不应生成任何时段");
        assertEquals(days - countMondays(from, days), openDays, "营业天数 = 区间天数 - 周一数");
    }

    private int countMondays(LocalDate from, int days) {
        int n = 0;
        for (int i = 0; i < days; i++) {
            if (from.plusDays(i).getDayOfWeek().getValue() == 1) {
                n++;
            }
        }
        return n;
    }

    @Test
    void 重复生成幂等_不产生重复档期() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 8, 90);
        List<ServiceItem> items = List.of(item);
        LocalDate from = LocalDate.now();

        int first = slotGenerator.generateForShop(shop, items, from, 7);
        assertTrue(first > 0);

        int second = slotGenerator.generateForShop(shop, items, from, 7);
        assertEquals(0, second, "第二次生成必须全部跳过");

        Integer duplicates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT service_item_id, slot_date, start_time, COUNT(*) c " +
                        "FROM time_slots GROUP BY service_item_id, slot_date, start_time HAVING c > 1) t",
                Integer.class);
        assertEquals(0, duplicates, "库中不允许存在 (服务项,日期,开始时间) 重复的档期");
    }

    @Test
    void 预览数与实际生成数一致() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大包间 10 人", 2, 150);
        List<ServiceItem> items = List.of(item);
        LocalDate from = LocalDate.now();

        int preview = slotGenerator.previewCount(shop, items, from, 7);
        int created = slotGenerator.generateForShop(shop, items, from, 7);
        assertEquals(preview, created, "老板在向导里看到的预览数必须与实际一致");
    }

    @Test
    void 滚动补期_档期被误删后自动补回() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "美甲", 3, 60);
        slotGenerator.generateForItem(shop, item, LocalDate.now(), 3);

        // 模拟未来某天档期意外缺失
        LocalDate target = futureOpenDate(2);
        int deleted = jdbc.update(
                "DELETE FROM time_slots WHERE service_item_id = ? AND slot_date = ?",
                item.getId(), target);
        assertTrue(deleted > 0, "前置条件：确实删掉了一天档期");

        slotGenerator.rollingRenew();

        Integer restored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM time_slots WHERE service_item_id = ? AND slot_date = ?",
                Integer.class, item.getId(), target);
        assertEquals(deleted, restored, "滚动补期必须把缺失的档期补齐");
    }

    @Test
    void 下架服务不参与生成() {
        Shop shop = createCompletedShop();
        ServiceItem active = createItem(shop, "在售项目", 2, 60);
        ServiceItem inactive = createItem(shop, "停售项目", 2, 60);
        jdbc.update("UPDATE service_items SET status = 'INACTIVE' WHERE id = ?", inactive.getId());
        // 生成器读取的是实体状态，从数据库重新加载
        ServiceItem inactiveFromDb = itemMapper.selectById(inactive.getId());

        int created = slotGenerator.generateForShop(shop, List.of(active, inactiveFromDb), LocalDate.now(), 3);

        Integer inactiveSlots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM time_slots WHERE service_item_id = ?",
                Integer.class, inactive.getId());
        assertEquals(0, inactiveSlots, "下架服务绝不生成档期");
        assertTrue(created > 0);
    }
}
