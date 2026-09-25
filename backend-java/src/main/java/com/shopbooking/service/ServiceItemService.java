package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.ServiceItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ServiceItemService {

    private final ServiceItemMapper itemMapper;
    private final SlotGeneratorService slotGenerator;
    private final ShopService shopService;

    public ServiceItemService(ServiceItemMapper itemMapper, SlotGeneratorService slotGenerator, ShopService shopService) {
        this.itemMapper = itemMapper;
        this.slotGenerator = slotGenerator;
        this.shopService = shopService;
    }

    public List<ServiceItem> listActive(Long shopId) {
        return itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shopId)
                .eq(ServiceItem::getStatus, "ACTIVE")
                .orderByAsc(ServiceItem::getSortOrder).orderByAsc(ServiceItem::getId));
    }

    public List<ServiceItem> listAll(Long shopId) {
        return itemMapper.selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getShopId, shopId)
                .orderByAsc(ServiceItem::getSortOrder).orderByAsc(ServiceItem::getId));
    }

    public ServiceItem require(Long id) {
        ServiceItem item = itemMapper.selectById(id);
        if (item == null) {
            throw BusinessException.notFound("服务项目不存在");
        }
        return item;
    }

    /** 新增服务项：自动生成未来 N 天档期 */
    @Transactional
    public ServiceItem create(Long shopId, Map<String, Object> body) {
        ServiceItem item = fromBody(new ServiceItem(), body);
        item.setShopId(shopId);
        if (item.getUnitCount() == null || item.getUnitCount() < 1) {
            item.setUnitCount(1);
        }
        if (item.getCapacityPerUnit() == null || item.getCapacityPerUnit() < 1) {
            item.setCapacityPerUnit(1);
        }
        if (item.getStatus() == null) {
            item.setStatus("ACTIVE");
        }
        itemMapper.insert(item);
        Shop shop = shopService.requirePrimaryShop();
        if (Boolean.TRUE.equals(shop.getSetupCompleted()) && "ACTIVE".equals(item.getStatus())) {
            slotGenerator.generateForItem(shop, item, LocalDate.now(), slotGenerator.defaultDays());
        }
        return item;
    }

    @Transactional
    public ServiceItem update(Long id, Map<String, Object> body) {
        ServiceItem item = require(id);
        fromBody(item, body);
        itemMapper.updateById(item);
        // 容量变化同步到未来空闲档期
        if (body.containsKey("unitCount") && item.getUnitCount() != null) {
            slotGenerator.syncFutureCapacity(item);
        }
        return item;
    }

    /** 上下架：下架时关闭未来空闲档期，上架时重新生成 */
    @Transactional
    public void changeStatus(Long id, String status) {
        ServiceItem item = require(id);
        item.setStatus(status);
        itemMapper.updateById(item);
        Shop shop = shopService.requirePrimaryShop();
        if (Boolean.TRUE.equals(shop.getSetupCompleted())) {
            if ("INACTIVE".equals(status)) {
                slotGenerator.closeFutureFreeSlots(item);
            } else {
                slotGenerator.generateForItem(shop, item, LocalDate.now(), slotGenerator.defaultDays());
            }
        }
    }

    @Transactional
    public void delete(Long id) {
        ServiceItem item = require(id);
        long futureBookings = slotGenerator.countFutureBookings(item);
        if (futureBookings > 0) {
            throw BusinessException.conflict("该服务还有 " + futureBookings + " 条未来预约，请先处理后再删除（可先下架）");
        }
        itemMapper.deleteById(id);
        slotGenerator.deleteFutureFreeSlots(item, LocalDate.now());
    }

    private ServiceItem fromBody(ServiceItem item, Map<String, Object> body) {
        if (body.containsKey("name")) {
            String name = String.valueOf(body.get("name")).trim();
            if (name.isEmpty() || name.length() > 100) {
                throw BusinessException.badRequest("服务名称必填（100 字以内）");
            }
            item.setName(name);
        }
        if (body.containsKey("capacityPerUnit")) {
            int v = toInt(body.get("capacityPerUnit"), 1);
            if (v < 1 || v > 999) {
                throw BusinessException.badRequest("容纳人数需在 1-999 之间");
            }
            item.setCapacityPerUnit(v);
        }
        if (body.containsKey("unitCount")) {
            int v = toInt(body.get("unitCount"), 1);
            if (v < 1 || v > 999) {
                throw BusinessException.badRequest("数量需在 1-999 之间");
            }
            item.setUnitCount(v);
        }
        if (body.containsKey("durationMinutes")) {
            int v = toInt(body.get("durationMinutes"), 60);
            if (v < 15 || v > 720) {
                throw BusinessException.badRequest("单次时长需在 15-720 分钟之间");
            }
            item.setDurationMinutes(v);
        }
        if (body.containsKey("price")) {
            Object p = body.get("price");
            item.setPrice(p == null || String.valueOf(p).isBlank() ? null : new BigDecimal(String.valueOf(p)));
        }
        if (body.containsKey("advanceDays")) {
            int v = toInt(body.get("advanceDays"), 30);
            if (v < 1 || v > 90) {
                throw BusinessException.badRequest("可预约提前天数需在 1-90 之间");
            }
            item.setAdvanceDays(v);
        }
        if (body.containsKey("cancelPolicy")) {
            item.setCancelPolicy(str(body.get("cancelPolicy")));
        }
        if (body.containsKey("sortOrder")) {
            item.setSortOrder(toInt(body.get("sortOrder"), 0));
        }
        if (body.containsKey("status")) {
            item.setStatus(String.valueOf(body.get("status")));
        }
        return item;
    }

    private int toInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw BusinessException.badRequest("数字格式不正确：" + v);
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 行业模板（餐饮/美业/家政），开店向导与服务项页一键套用 */
    public Map<String, List<Map<String, Object>>> templates() {
        return Map.of(
                "restaurant", List.of(
                        tpl("大厅 2 人桌", 2, 6, 90, null),
                        tpl("大厅 4 人桌", 4, 8, 90, null),
                        tpl("小包间 6 人", 6, 3, 120, null),
                        tpl("大包间 10 人", 10, 2, 150, null)),
                "beauty", List.of(
                        tpl("美甲", 1, 3, 60, "88"),
                        tpl("美睫", 1, 2, 90, "128"),
                        tpl("洗剪吹", 1, 4, 45, "58")),
                "housekeeping", List.of(
                        tpl("日常保洁 2 小时", 1, 5, 120, "120"),
                        tpl("深度保洁 4 小时", 1, 3, 240, "280")));
    }

    private Map<String, Object> tpl(String name, int capacityPerUnit, int unitCount,
                                    int durationMinutes, String price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("capacityPerUnit", capacityPerUnit);
        m.put("unitCount", unitCount);
        m.put("durationMinutes", durationMinutes);
        if (price != null) {
            m.put("price", price);
        }
        return m;
    }
}
