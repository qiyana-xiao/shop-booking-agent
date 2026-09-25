package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.Shop;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.SlotGeneratorService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 店铺配置（仅老板）：营业时间变更时自动重建未来空闲档期，已有预约原样保留 */
@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final ShopService shopService;
    private final SlotGeneratorService slotGenerator;
    private final AuditService auditService;

    public ShopController(ShopService shopService, SlotGeneratorService slotGenerator, AuditService auditService) {
        this.shopService = shopService;
        this.slotGenerator = slotGenerator;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> get() {
        Shop shop = shopService.requirePrimaryShop();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", shop.getId());
        m.put("name", shop.getName());
        m.put("address", shop.getAddress());
        m.put("phone", shop.getPhone());
        m.put("slotGranularityMinutes", shop.getSlotGranularityMinutes());
        m.put("setupCompleted", shop.getSetupCompleted());
        m.put("hours", hoursView(shop));
        m.put("hoursText", shopService.hoursText(shop));
        return m;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        Shop shop = shopService.requirePrimaryShop();
        String previousHours = shop.getOpenHours();

        if (body.containsKey("name")) {
            String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
            if (name.isEmpty() || name.length() > 100) {
                throw BusinessException.badRequest("店铺名称必填（100 字以内）");
            }
            shop.setName(name);
        }
        if (body.containsKey("address")) {
            shop.setAddress(trimOrNull(body.get("address")));
        }
        if (body.containsKey("phone")) {
            shop.setPhone(trimOrNull(body.get("phone")));
        }
        if (body.containsKey("slotGranularityMinutes")) {
            int v = Integer.parseInt(String.valueOf(body.get("slotGranularityMinutes")));
            if (v < 15 || v > 240) {
                throw BusinessException.badRequest("时段粒度需在 15-240 分钟之间");
            }
            shop.setSlotGranularityMinutes(v);
        }
        if (body.containsKey("hours") && body.get("hours") instanceof Map<?, ?> hoursMap) {
            shop.setOpenHours(serializeHours(hoursMap));
        }
        shopService.update(shop);

        Map<String, Integer> rebuild = null;
        if (body.containsKey("hours") && previousHours != null
                && !previousHours.equals(shop.getOpenHours())) {
            rebuild = slotGenerator.rebuildFutureFreeSlots(shop);
            auditService.audit(currentUser(), "SHOP_UPDATE", "shop:" + shop.getId(),
                    "营业时间变更，重建档期：" + rebuild);
        } else {
            auditService.audit(currentUser(), "SHOP_UPDATE", "shop:" + shop.getId(), null);
        }

        Map<String, Object> result = get();
        if (rebuild != null) {
            result.put("slotsRebuilt", rebuild);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String serializeHours(Map<?, ?> hoursMap) {
        Map<DayOfWeek, ShopService.HoursRange> parsed = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            parsed.put(day, new ShopService.HoursRange("09:00", "21:00", false));
        }
        for (Map.Entry<?, ?> e : hoursMap.entrySet()) {
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(String.valueOf(e.getKey()).toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw BusinessException.badRequest("星期格式不正确：" + e.getKey());
            }
            if (e.getValue() instanceof Map<?, ?> block) {
                boolean closed = Boolean.TRUE.equals(block.get("closed"));
                String open = block.get("open") == null ? "09:00" : String.valueOf(block.get("open"));
                String close = block.get("close") == null ? "21:00" : String.valueOf(block.get("close"));
                try {
                    java.time.LocalTime.parse(open);
                    java.time.LocalTime.parse(close);
                } catch (Exception ex) {
                    throw BusinessException.badRequest("营业时间格式不正确：" + open + " - " + close);
                }
                parsed.put(day, new ShopService.HoursRange(open, close, closed));
            }
        }
        return shopService.hoursToJson(parsed);
    }

    private Map<String, Object> hoursView(Shop shop) {
        Map<DayOfWeek, ShopService.HoursRange> hours = shopService.parseHours(shop);
        List<String> order = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        Map<String, Object> view = new LinkedHashMap<>();
        for (String day : order) {
            ShopService.HoursRange h = hours.get(DayOfWeek.valueOf(day));
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("open", h.open());
            v.put("close", h.close());
            v.put("closed", h.closed());
            view.put(day, v);
        }
        return view;
    }

    private String trimOrNull(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
