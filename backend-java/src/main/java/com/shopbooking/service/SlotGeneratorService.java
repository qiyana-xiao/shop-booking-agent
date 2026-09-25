package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import com.shopbooking.mapper.ServiceItemMapper;
import com.shopbooking.mapper.TimeSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 档期生成器：老板填的是规则（营业时间+服务项），系统把规则展开成档期。
 * 生成是幂等的：已存在的 (服务项, 日期, 开始时间) 跳过，绝不重复插入。
 */
@Service
public class SlotGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(SlotGeneratorService.class);

    private final TimeSlotMapper slotMapper;
    private final ServiceItemMapper itemMapper;
    private final ShopService shopService;
    private final AppProperties props;

    public SlotGeneratorService(TimeSlotMapper slotMapper, ServiceItemMapper itemMapper,
                                ShopService shopService, AppProperties props) {
        this.slotMapper = slotMapper;
        this.itemMapper = itemMapper;
        this.shopService = shopService;
        this.props = props;
    }

    public int defaultDays() {
        return props.getBooking().getSlotDays();
    }

    /** 预览：给定店铺与服务项，将生成多少条档期（不落库） */
    public int previewCount(Shop shop, List<ServiceItem> items, LocalDate from, int days) {
        Map<DayOfWeek, ShopService.HoursRange> hours = shopService.parseHours(shop);
        int granularity = shop.getSlotGranularityMinutes() == null ? 60 : shop.getSlotGranularityMinutes();
        int count = 0;
        for (ServiceItem item : items) {
            if (!"ACTIVE".equals(item.getStatus())) {
                continue;
            }
            for (int d = 0; d < days; d++) {
                LocalDate date = from.plusDays(d);
                ShopService.HoursRange range = hours.get(date.getDayOfWeek());
                if (range == null || range.closed()) {
                    continue;
                }
                count += countSlotsForDay(range, granularity, item.getDurationMinutes());
            }
        }
        return count;
    }

    private int countSlotsForDay(ShopService.HoursRange range, int granularity, int durationMinutes) {
        int count = 0;
        LocalTime start = range.openTime();
        LocalTime close = range.closeTime();
        while (!start.plusMinutes(durationMinutes).isAfter(close)) {
            count++;
            start = start.plusMinutes(granularity);
            if (!start.isBefore(close)) {
                break;
            }
        }
        return count;
    }

    /** 为整店生成档期（幂等，已存在跳过） */
    @Transactional
    public int generateForShop(Shop shop, List<ServiceItem> items, LocalDate from, int days) {
        int created = 0;
        for (ServiceItem item : items) {
            created += generateForItemInternal(shop, item, from, days);
        }
        if (created > 0) {
            log.info("档期生成完成：shop={} 新增 {} 条", shop.getId(), created);
        }
        return created;
    }

    /** 为单个服务项生成档期（新增服务时调用） */
    @Transactional
    public int generateForItem(Shop shop, ServiceItem item, LocalDate from, int days) {
        return generateForItemInternal(shop, item, from, days);
    }

    private int generateForItemInternal(Shop shop, ServiceItem item, LocalDate from, int days) {
        if (!"ACTIVE".equals(item.getStatus())) {
            return 0;
        }
        Map<DayOfWeek, ShopService.HoursRange> hours = shopService.parseHours(shop);
        int granularity = shop.getSlotGranularityMinutes() == null ? 60 : shop.getSlotGranularityMinutes();
        LocalDate to = from.plusDays(days - 1);

        Set<String> existing = new HashSet<>();
        List<TimeSlot> existingSlots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .between(TimeSlot::getSlotDate, from, to)
                .select(TimeSlot::getSlotDate, TimeSlot::getStartTime));
        for (TimeSlot s : existingSlots) {
            existing.add(s.getSlotDate() + "|" + s.getStartTime());
        }

        List<TimeSlot> toInsert = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate date = from.plusDays(d);
            ShopService.HoursRange range = hours.get(date.getDayOfWeek());
            if (range == null || range.closed()) {
                continue;
            }
            LocalTime start = range.openTime();
            LocalTime close = range.closeTime();
            while (!start.plusMinutes(item.getDurationMinutes()).isAfter(close)) {
                String key = date + "|" + start;
                if (!existing.contains(key)) {
                    TimeSlot slot = new TimeSlot();
                    slot.setShopId(shop.getId());
                    slot.setServiceItemId(item.getId());
                    slot.setSlotDate(date);
                    slot.setStartTime(start);
                    slot.setEndTime(start.plusMinutes(item.getDurationMinutes()));
                    slot.setCapacity(item.getUnitCount() == null ? 1 : item.getUnitCount());
                    slot.setBookedCount(0);
                    slot.setVersion(0);
                    slot.setStatus("OPEN");
                    toInsert.add(slot);
                }
                start = start.plusMinutes(granularity);
                if (!start.isBefore(close)) {
                    break;
                }
            }
        }
        for (TimeSlot slot : toInsert) {
            slotMapper.insert(slot);
        }
        return toInsert.size();
    }

    /** 每天凌晨滚动补期：保证未来 N 天档期永远存在 */
    @Transactional
    public void rollingRenew() {
        Shop shop = shopService.primaryShop();
        if (shop == null || !Boolean.TRUE.equals(shop.getSetupCompleted())) {
            return;
        }
        List<ServiceItem> items = itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shop.getId())
                .eq(ServiceItem::getStatus, "ACTIVE"));
        generateForShop(shop, items, LocalDate.now(), props.getBooking().getSlotDays() + 1);
    }

    /** 营业时间变更：删除未来空闲档期并按新规则重建（已有预约的档期原样保留） */
    @Transactional
    public Map<String, Integer> rebuildFutureFreeSlots(Shop shop) {
        List<ServiceItem> items = itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shop.getId())
                .eq(ServiceItem::getStatus, "ACTIVE"));
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        int deleted = 0;
        for (ServiceItem item : items) {
            deleted += deleteFutureFreeSlots(item, tomorrow);
        }
        int created = generateForShop(shop, items, tomorrow, props.getBooking().getSlotDays());
        return Map.of("deleted", deleted, "created", created);
    }

    public int deleteFutureFreeSlots(ServiceItem item, LocalDate from) {
        List<TimeSlot> free = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .ge(TimeSlot::getSlotDate, from)
                .eq(TimeSlot::getStatus, "OPEN")
                .eq(TimeSlot::getBookedCount, 0)
                .select(TimeSlot::getId));
        for (TimeSlot s : free) {
            slotMapper.deleteById(s.getId());
        }
        return free.size();
    }

    /** 容量同步：未来空闲档期的 capacity 收缩时跟随 unit_count */
    public void syncFutureCapacity(ServiceItem item) {
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .ge(TimeSlot::getSlotDate, LocalDate.now())
                .eq(TimeSlot::getBookedCount, 0));
        for (TimeSlot s : slots) {
            if (s.getCapacity() != null && item.getUnitCount() != null
                    && s.getCapacity() > item.getUnitCount()) {
                s.setCapacity(item.getUnitCount());
                slotMapper.updateById(s);
            }
        }
    }

    public void closeFutureFreeSlots(ServiceItem item) {
        List<TimeSlot> slots = slotMapper.selectList(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .ge(TimeSlot::getSlotDate, LocalDate.now())
                .eq(TimeSlot::getBookedCount, 0)
                .eq(TimeSlot::getStatus, "OPEN"));
        for (TimeSlot s : slots) {
            s.setStatus("CLOSED");
            slotMapper.updateById(s);
        }
    }

    public long countFutureBookings(ServiceItem item) {
        return slotMapper.selectCount(new LambdaQueryWrapper<TimeSlot>()
                .eq(TimeSlot::getServiceItemId, item.getId())
                .ge(TimeSlot::getSlotDate, LocalDate.now())
                .gt(TimeSlot::getBookedCount, 0));
    }
}
