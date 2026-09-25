package com.shopbooking.service;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.ToolRegistry;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等保护：同参数重复 hold_slot 只锁一次。
 * Redis 降级场景下由数据库唯一索引（uk_slot_customer 生成列）兜底。
 */
class IdempotencyTest extends IntegrationTestBase {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private ConversationService conversationService;

    @Test
    void 同会话重复锁座_返回同一预约单() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 8, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(1), LocalTime.of(19, 0), 90, 8);

        Map<String, Object> first = bookingService.hold(slot.getId(), 4, "same-session", null);
        Map<String, Object> second = bookingService.hold(slot.getId(), 4, "same-session", null);

        assertEquals(first.get("bookingNo"), second.get("bookingNo"), "重复锁座必须返回首次结果");
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount());
    }

    @Test
    void 工具层同参数重复调用_只生效一次() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 8, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(2), LocalTime.of(19, 0), 90, 8);
        Conversation conv = conversationService.getOrCreate(shop.getId(), "idem-tool-session", null);
        AgentContext ctx = new AgentContext(conv.getId(), shop.getId(), "idem-tool-session", null);

        ToolResult first = toolRegistry.invoke("hold_slot",
                Map.of("slotId", slot.getId(), "partySize", 4), ctx, 1);
        ToolResult second = toolRegistry.invoke("hold_slot",
                Map.of("slotId", slot.getId(), "partySize", 4), ctx, 1);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(first.getData().get("bookingNo"), second.getData().get("bookingNo"));
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount(),
                "工具重复调用不能重复占座");
    }

    @Test
    void 取消后可重新预约同时段() {
        Shop shop = createCompletedShop();
        ServiceItem item = createItem(shop, "大厅 4 人桌", 8, 90);
        TimeSlot slot = createSlot(shop, item, futureOpenDate(3), LocalTime.of(19, 0), 90, 8);

        Map<String, Object> first = bookingService.hold(slot.getId(), 4, "rebook-session", null);
        bookingService.cancel((String) first.get("bookingNo"), "顾客改主意", "rebook-session", null, null);
        assertEquals(0, slotMapper.selectById(slot.getId()).getBookedCount());

        Map<String, Object> again = bookingService.hold(slot.getId(), 4, "rebook-session", null);
        assertNotNull(again.get("bookingNo"));
        assertNotEquals(first.get("bookingNo"), again.get("bookingNo"));
        assertEquals(1, slotMapper.selectById(slot.getId()).getBookedCount(),
                "取消后生成列唯一键应释放，允许重新预约同时段");
    }
}
