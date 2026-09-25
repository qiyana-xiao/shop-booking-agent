package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.Booking;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.TimeSlot;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.mapper.ServiceItemMapper;
import com.shopbooking.mapper.TimeSlotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SlotService {

    private final TimeSlotMapper slotMapper;
    private final ServiceItemMapper itemMapper;
    private final BookingMapper bookingMapper;
    private final BookingService bookingService;

    public SlotService(TimeSlotMapper slotMapper, ServiceItemMapper itemMapper,
                       BookingMapper bookingMapper, BookingService bookingService) {
        this.slotMapper = slotMapper;
        this.itemMapper = itemMapper;
        this.bookingMapper = bookingMapper;
        this.bookingService = bookingService;
    }

    /**
     * 懒清理过期档期：整天已过或当天已结束的时段标记为 EXPIRED，
     * 让月历/日明细自动"翻篇"，不再把昨天的档期当开放状态展示。
     * 单条 UPDATE 自身原子；被同类方法调用，不加事务注解（自调用不经过代理）。
     */
    public int markExpiredSlots(Long shopId) {
        LocalDate today = LocalDate.now();
        java.time.LocalTime now = java.time.LocalTime.now();
        return slotMapper.update(null, new LambdaUpdateWrapper<TimeSlot>()
                .eq(TimeSlot::getShopId, shopId)
                .eq(TimeSlot::getStatus, "OPEN")
                .and(q -> q.lt(TimeSlot::getSlotDate, today)
                        .or(sub -> sub.eq(TimeSlot::getSlotDate, today)
                                .le(TimeSlot::getEndTime, now)))
                .set(TimeSlot::getStatus, "EXPIRED"));
    }

    /**
     * 档期查询（Agent 工具与页面共用）：先懒清理过期锁座，保证余量是实时的。
     * 返回 {date, slots:[...], nextAvailableDates:[...]}
     */
    public Map<String, Object> checkAvailability(Long shopId, LocalDate date, String startTime,
                                                 Integer partySize, Long serviceItemId) {
        if (date == null) {
            date = LocalDate.now();
        }
        bookingService.releaseExpiredHolds();
        markExpiredSlots(shopId);

        List<ServiceItem> items = itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shopId)
                .eq(ServiceItem::getStatus, "ACTIVE")
                .orderByAsc(ServiceItem::getSortOrder));
        if (serviceItemId != null) {
            items = items.stream().filter(i -> serviceItemId.equals(i.getId())).toList();
        }
        if (partySize != null) {
            // 人数筛选：优先推荐容纳得下且最小的服务类型
            items = items.stream().filter(i -> i.getCapacityPerUnit() >= partySize).toList();
        }
        Map<Long, ServiceItem> itemById = new LinkedHashMap<>();
        for (ServiceItem item : items) {
            itemById.put(item.getId(), item);
        }

        List<Map<String, Object>> slotViews = new ArrayList<>();
        if (!itemById.isEmpty()) {
            LambdaQueryWrapper<TimeSlot> q = new LambdaQueryWrapper<TimeSlot>()
                    .in(TimeSlot::getServiceItemId, itemById.keySet())
                    .eq(TimeSlot::getShopId, shopId)
                    .eq(TimeSlot::getSlotDate, date)
                    .eq(TimeSlot::getStatus, "OPEN")
                    .ge(TimeSlot::getStartTime, java.time.LocalTime.MIN)
                    .orderByAsc(TimeSlot::getStartTime).orderByAsc(TimeSlot::getServiceItemId);
            if (startTime != null && !startTime.isBlank()) {
                try {
                    q.ge(TimeSlot::getStartTime, java.time.LocalTime.parse(startTime));
                } catch (Exception ignored) {
                }
            }
            LocalDateTime now = LocalDateTime.now();
            for (TimeSlot slot : slotMapper.selectList(q)) {
                ServiceItem item = itemById.get(slot.getServiceItemId());
                if (item == null) {
                    continue;
                }
                if (date.isAfter(LocalDate.now().plusDays(item.getAdvanceDays()))) {
                    continue;
                }
                if (LocalDateTime.of(slot.getSlotDate(), slot.getStartTime()).isBefore(now)) {
                    continue;
                }
                int remaining = slot.getCapacity() - slot.getBookedCount();
                if (remaining <= 0) {
                    continue;
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("slotId", slot.getId());
                v.put("serviceItemId", item.getId());
                v.put("serviceName", item.getName());
                v.put("startTime", slot.getStartTime().toString());
                v.put("endTime", slot.getEndTime().toString());
                v.put("remaining", remaining);
                v.put("capacity", slot.getCapacity());
                v.put("capacityPerUnit", item.getCapacityPerUnit());
                v.put("price", item.getPrice() == null ? null : item.getPrice().toPlainString());
                slotViews.add(v);
            }
        }

        // 没档期时给出"接下来哪几天有位"，帮模型主动推荐备选
        List<String> nextDates = new ArrayList<>();
        if (slotViews.isEmpty()) {
            for (int d = 1; d <= 14 && nextDates.size() < 5; d++) {
                LocalDate candidate = date.plusDays(d);
                if (hasAnyAvailability(shopId, items, candidate)) {
                    nextDates.add(candidate.toString());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("slots", slotViews);
        result.put("nextAvailableDates", nextDates);
        result.put("total", slotViews.size());
        return result;
    }

    private boolean hasAnyAvailability(Long shopId, List<ServiceItem> items, LocalDate date) {
        if (items.isEmpty()) {
            return false;
        }
        List<Long> ids = items.stream().map(ServiceItem::getId).toList();
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .in(TimeSlot::getServiceItemId, ids)
                .eq(TimeSlot::getSlotDate, date)
                .eq(TimeSlot::getStatus, "OPEN"));
        LocalDateTime now = LocalDateTime.now();
        for (TimeSlot slot : slots) {
            if (LocalDateTime.of(slot.getSlotDate(), slot.getStartTime()).isBefore(now)) {
                continue;
            }
            if (slot.getCapacity() - slot.getBookedCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 月历：每天的 总档期/开放/已约/剩余/是否休息 聚合（过去的日期带 past 标记） */
    public List<Map<String, Object>> monthCalendar(Long shopId, int year, int month) {
        markExpiredSlots(shopId);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate today = LocalDate.now();
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getShopId, shopId)
                .between(TimeSlot::getSlotDate, from, to));

        Map<String, Map<String, Integer>> agg = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("total", 0);
            m.put("open", 0);
            m.put("booked", 0);
            m.put("remaining", 0);
            agg.put(d.toString(), m);
        }
        for (TimeSlot slot : slots) {
            Map<String, Integer> m = agg.get(slot.getSlotDate().toString());
            if (m == null) {
                continue;
            }
            m.merge("total", 1, Integer::sum);
            if ("OPEN".equals(slot.getStatus())) {
                m.merge("open", 1, Integer::sum);
                m.merge("booked", slot.getBookedCount(), Integer::sum);
                m.merge("remaining", Math.max(0, slot.getCapacity() - slot.getBookedCount()), Integer::sum);
            }
        }
        List<Map<String, Object>> days = new ArrayList<>();
        agg.forEach((date, m) -> {
            Map<String, Object> day = new LinkedHashMap<>(m);
            LocalDate d = LocalDate.parse(date);
            boolean past = d.isBefore(today);
            day.put("date", date);
            day.put("past", past);
            // 休息日 = 当天营业时间内无开放时段；过去的日期不算休息日（是"已结束"）
            day.put("restDay", !past && m.get("open") == 0 && m.get("total") > 0);
            days.add(day);
        });
        return days;
    }

    /** 某日档期明细（按服务分组） */
    public Map<String, Object> dayDetail(Long shopId, LocalDate date) {
        markExpiredSlots(shopId);
        List<ServiceItem> items = itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shopId)
                .orderByAsc(ServiceItem::getSortOrder));
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getShopId, shopId)
                .eq(TimeSlot::getSlotDate, date)
                .orderByAsc(TimeSlot::getStartTime));

        Map<Long, List<Map<String, Object>>> byItem = new LinkedHashMap<>();
        for (ServiceItem item : items) {
            byItem.put(item.getId(), new ArrayList<>());
        }
        for (TimeSlot slot : slots) {
            List<Map<String, Object>> list = byItem.computeIfAbsent(slot.getServiceItemId(), k -> new ArrayList<>());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("slotId", slot.getId());
            v.put("startTime", slot.getStartTime().toString());
            v.put("endTime", slot.getEndTime().toString());
            v.put("capacity", slot.getCapacity());
            v.put("bookedCount", slot.getBookedCount());
            v.put("remaining", Math.max(0, slot.getCapacity() - slot.getBookedCount()));
            v.put("status", slot.getStatus());
            list.add(v);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        byItem.forEach((itemId, list) -> {
            ServiceItem item = items.stream().filter(i -> i.getId().equals(itemId)).findFirst().orElse(null);
            if (item == null || list.isEmpty()) {
                return;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("serviceItemId", itemId);
            g.put("serviceName", item.getName());
            g.put("slots", list);
            groups.add(g);
        });

        long affectedBookings = slots.stream().filter(s -> s.getBookedCount() > 0).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("past", date.isBefore(LocalDate.now()));
        result.put("groups", groups);
        result.put("affectedBookings", affectedBookings);
        return result;
    }

    /** 整天休息/恢复：空闲档期关闭，已有预约的保留（返回受影响预约数，老板需逐个通知） */
    @Transactional
    public Map<String, Object> dayClose(Long shopId, LocalDate date, boolean closed) {
        if (!date.isAfter(LocalDate.now())) {
            throw BusinessException.badRequest("只能设置未来日期的休息安排");
        }
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getShopId, shopId)
                .eq(TimeSlot::getSlotDate, date));
        long affectedBookings = 0;
        int changed = 0;
        for (TimeSlot slot : slots) {
            if (closed) {
                if ("OPEN".equals(slot.getStatus())) {
                    if (slot.getBookedCount() > 0) {
                        affectedBookings += slot.getBookedCount(); // 有预约的保留并标红提醒
                    } else {
                        slot.setStatus("CLOSED");
                        slotMapper.updateById(slot);
                        changed++;
                    }
                }
            } else {
                if ("CLOSED".equals(slot.getStatus()) && slot.getBookedCount() == 0) {
                    slot.setStatus("OPEN");
                    slotMapper.updateById(slot);
                    changed++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("closed", closed);
        result.put("changedSlots", changed);
        result.put("affectedBookings", affectedBookings);
        return result;
    }

    /** 改某时段容量（只影响该时段，不影响其他） */
    @Transactional
    public Map<String, Object> updateCapacity(Long slotId, int capacity) {
        TimeSlot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw BusinessException.notFound("档期不存在");
        }
        if (capacity < 0 || capacity > 999) {
            throw BusinessException.badRequest("容量需在 0-999 之间");
        }
        if (capacity < slot.getBookedCount()) {
            throw BusinessException.badRequest("容量不能小于已预约数（当前已约 " + slot.getBookedCount() + "）");
        }
        slot.setCapacity(capacity);
        slotMapper.updateById(slot);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slotId", slotId);
        result.put("capacity", capacity);
        result.put("bookedCount", slot.getBookedCount());
        return result;
    }

    /** 当日有效预约（看板今日列表用） */
    public List<Map<String, Object>> todayViews(Long shopId, LocalDate date) {
        return bookingMapper.listDayViews(shopId, date);
    }

    /** 统计某日受影响预约数（休息设置前的预览） */
    public long countDayBookings(Long shopId, LocalDate date) {
        List<Long> slotIds = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                        .eq(TimeSlot::getShopId, shopId)
                        .eq(TimeSlot::getSlotDate, date)
                        .select(TimeSlot::getId))
                .stream().map(TimeSlot::getId).toList();
        if (slotIds.isEmpty()) {
            return 0;
        }
        return bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .in(Booking::getSlotId, slotIds)
                .in(Booking::getStatus, List.of(Booking.STATUS_HELD, Booking.STATUS_CONFIRMED)));
    }
}
