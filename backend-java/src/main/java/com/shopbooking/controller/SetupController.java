package com.shopbooking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.dto.SetupWizardRequest;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.UserMapper;
import com.shopbooking.entity.User;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.ServiceItemService;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.SlotGeneratorService;
import com.shopbooking.service.DeepSeekService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 4 步开店向导：店铺信息 → 营业时间 → 服务项 → 确认生成档期。
 * 向导幂等：重复提交按更新处理，服务项追加而不清空（保护已产生的预约数据）。
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final ShopService shopService;
    private final ServiceItemService itemService;
    private final SlotGeneratorService slotGenerator;
    private final UserMapper userMapper;
    private final DeepSeekService deepSeek;
    private final AuditService auditService;

    public SetupController(ShopService shopService, ServiceItemService itemService,
                           SlotGeneratorService slotGenerator, UserMapper userMapper,
                           DeepSeekService deepSeek, AuditService auditService) {
        this.shopService = shopService;
        this.itemService = itemService;
        this.slotGenerator = slotGenerator;
        this.userMapper = userMapper;
        this.deepSeek = deepSeek;
        this.auditService = auditService;
    }

    /** 前端首页据此决定展示"开店向导"还是顾客对话窗（无敏感信息，可匿名访问） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Shop shop = shopService.primaryShop();
        boolean hasOwner = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getRole, "owner")) > 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopExists", shop != null);
        m.put("setupCompleted", shop != null && Boolean.TRUE.equals(shop.getSetupCompleted()));
        m.put("hasOwner", hasOwner);
        m.put("deepSeekConfigured", deepSeek.configured());
        if (shop != null) {
            m.put("shopName", shop.getName());
            if (shop.getPhone() != null && !shop.getPhone().isBlank()) {
                m.put("shopPhone", shop.getPhone());
            }
        }
        return m;
    }

    @PostMapping("/wizard")
    public Map<String, Object> wizard(@RequestBody SetupWizardRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) {
            throw BusinessException.badRequest("店铺名称必填（100 字以内）");
        }

        Shop shop = shopService.primaryShop();
        boolean firstTime = shop == null;
        if (shop == null) {
            shop = new Shop();
            shop.setSetupCompleted(false);
        }
        shop.setName(req.name().trim());
        shop.setAddress(req.address() == null ? null : req.address().trim());
        shop.setPhone(req.phone() == null ? null : req.phone().trim());
        if (req.slotGranularityMinutes() != null) {
            if (req.slotGranularityMinutes() < 15 || req.slotGranularityMinutes() > 240) {
                throw BusinessException.badRequest("时段粒度需在 15-240 分钟之间");
            }
            shop.setSlotGranularityMinutes(req.slotGranularityMinutes());
        } else if (shop.getSlotGranularityMinutes() == null) {
            shop.setSlotGranularityMinutes(60);
        }
        if (shop.getTimezone() == null || shop.getTimezone().isBlank()) {
            shop.setTimezone("Asia/Shanghai");
        }
        if (shop.getNotificationEnabled() == null) {
            shop.setNotificationEnabled(true);
        }
        shop.setOpenHours(hoursJson(req.hours()));
        if (firstTime) {
            shopService.save(shop);
        } else {
            shopService.update(shop);
        }

        int itemsCreated = 0;
        if (req.useTemplates() && req.industry() != null) {
            Map<String, List<Map<String, Object>>> templates = itemService.templates();
            List<Map<String, Object>> tpl = templates.get(req.industry());
            if (tpl == null) {
                throw BusinessException.badRequest("行业模板不存在，可选：restaurant / beauty / housekeeping");
            }
            List<String> existingNames = itemService.listAll(shop.getId())
                    .stream().map(ServiceItem::getName).toList();
            for (Map<String, Object> t : tpl) {
                String name = String.valueOf(t.get("name"));
                if (existingNames.contains(name)) {
                    continue; // 向导重复提交：同名服务项幂等跳过，不产生重复
                }
                itemService.create(shop.getId(), t);
                itemsCreated++;
            }
        } else if (req.serviceItems() != null && !req.serviceItems().isEmpty()) {
            if (req.serviceItems().size() > 50) {
                throw BusinessException.badRequest("服务项一次最多创建 50 个");
            }
            for (Map<String, Object> body : req.serviceItems()) {
                itemService.create(shop.getId(), body);
                itemsCreated++;
            }
        }

        List<ServiceItem> activeItems = itemService.listActive(shop.getId());
        if (activeItems.isEmpty()) {
            // 中断可恢复：服务项没配好不标记完成，重新提交向导即可继续
            throw BusinessException.badRequest("请至少配置一个服务项，或选择行业模板");
        }

        shop.setSetupCompleted(true);
        shopService.update(shop);
        int slotsGenerated = slotGenerator.generateForShop(shop, activeItems, LocalDate.now(),
                slotGenerator.defaultDays());

        auditService.audit(currentUser(), "SETUP_WIZARD", "shop:" + shop.getId(),
                "首次开店=" + firstTime + "，服务项 " + itemsCreated + " 个，生成档期 " + slotsGenerated + " 条");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shop.getId());
        result.put("firstTime", firstTime);
        result.put("serviceItemsCreated", itemsCreated);
        result.put("slotsGenerated", slotsGenerated);
        result.put("slotDays", slotGenerator.defaultDays());
        return result;
    }

    private String hoursJson(Map<String, SetupWizardRequest.HoursBlock> hours) {
        Map<DayOfWeek, ShopService.HoursRange> parsed = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            parsed.put(day, new ShopService.HoursRange("09:00", "21:00", false));
        }
        if (hours != null) {
            hours.forEach((dayName, block) -> {
                DayOfWeek day;
                try {
                    day = DayOfWeek.valueOf(dayName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw BusinessException.badRequest("星期格式不正确：" + dayName);
                }
                if (block == null) {
                    return;
                }
                if (block.closed()) {
                    parsed.put(day, new ShopService.HoursRange("09:00", "21:00", true));
                } else {
                    if (block.open() == null || block.close() == null) {
                        throw BusinessException.badRequest("营业时间不完整：" + dayName);
                    }
                    try {
                        java.time.LocalTime.parse(block.open());
                        java.time.LocalTime.parse(block.close());
                    } catch (Exception e) {
                        throw BusinessException.badRequest("营业时间格式不正确：" + block.open() + " - " + block.close());
                    }
                    parsed.put(day, new ShopService.HoursRange(block.open(), block.close(), false));
                }
            });
        }
        return shopService.hoursToJson(parsed);
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
