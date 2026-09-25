package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.ShopMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ShopService {

    private static final Logger log = LoggerFactory.getLogger(ShopService.class);

    public record HoursRange(String open, String close, boolean closed) {
        public LocalTime openTime() {
            return LocalTime.parse(open);
        }

        public LocalTime closeTime() {
            return LocalTime.parse(close);
        }
    }

    private final ShopMapper shopMapper;
    private final ObjectMapper objectMapper;

    public ShopService(ShopMapper shopMapper, ObjectMapper objectMapper) {
        this.shopMapper = shopMapper;
        this.objectMapper = objectMapper;
    }

    /** 单店部署：第一个店铺即本店（schema 预留 shop_id，支持未来多店） */
    public Shop primaryShop() {
        return shopMapper.selectList(new LambdaQueryWrapper<Shop>().orderByAsc(Shop::getId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    public Shop requirePrimaryShop() {
        Shop shop = primaryShop();
        if (shop == null) {
            throw BusinessException.notFound("店铺尚未创建，请先完成开店向导");
        }
        return shop;
    }

    public Map<DayOfWeek, HoursRange> parseHours(Shop shop) {
        Map<DayOfWeek, HoursRange> result = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            result.put(day, new HoursRange("09:00", "21:00", false));
        }
        if (shop == null || shop.getOpenHours() == null || shop.getOpenHours().isBlank()) {
            return result;
        }
        try {
            Map<String, Map<String, Object>> raw = objectMapper.readValue(shop.getOpenHours(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Map.class));
            for (DayOfWeek day : DayOfWeek.values()) {
                Map<String, Object> h = raw.get(day.name());
                if (h == null) {
                    continue;
                }
                String open = String.valueOf(h.getOrDefault("open", "09:00"));
                String close = String.valueOf(h.getOrDefault("close", "21:00"));
                boolean closed = Boolean.TRUE.equals(h.get("closed"));
                try {
                    LocalTime.parse(open);
                    LocalTime.parse(close);
                } catch (Exception e) {
                    throw BusinessException.badRequest("营业时间格式不正确：" + open + " - " + close);
                }
                result.put(day, new HoursRange(open, close, closed));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("营业时间解析失败，使用默认时间：{}", e.getMessage());
        }
        return result;
    }

    public String hoursText(Shop shop) {
        Map<DayOfWeek, HoursRange> hours = parseHours(shop);
        StringBuilder sb = new StringBuilder();
        String[] zh = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (DayOfWeek day : DayOfWeek.values()) {
            HoursRange h = hours.get(day);
            sb.append(zh[day.getValue() - 1]).append(" ")
                    .append(h.closed() ? "休息" : h.open() + "-" + h.close()).append("；");
        }
        return sb.toString();
    }

    public String hoursToJson(Map<DayOfWeek, HoursRange> hours) {
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        hours.forEach((day, h) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("open", h.open());
            m.put("close", h.close());
            m.put("closed", h.closed());
            raw.put(day.name(), m);
        });
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void validateHoursJson(String json) {
        try {
            Map<String, Map<String, Object>> raw = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Map.class));
            for (String day : raw.keySet()) {
                DayOfWeek.valueOf(day);
            }
            parseHours(new Shop() {{
                setOpenHours(json);
            }});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.badRequest("营业时间格式不正确");
        }
    }

    public boolean isOpenOn(Shop shop, LocalDate date) {
        HoursRange h = parseHours(shop).get(date.getDayOfWeek());
        return h != null && !h.closed();
    }

    public Shop save(Shop shop) {
        shopMapper.insert(shop);
        return shop;
    }

    public Shop update(Shop shop) {
        shopMapper.updateById(shop);
        return shopMapper.selectById(shop.getId());
    }
}
