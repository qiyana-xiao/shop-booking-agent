package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.ServiceItem;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.ServiceItemService;
import com.shopbooking.service.ShopService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 服务项配置（仅老板）：新增自动生成档期，下架关闭未来空闲档期，有未来预约不许删 */
@RestController
@RequestMapping("/api/service-items")
public class ServiceItemController {

    private final ServiceItemService itemService;
    private final ShopService shopService;
    private final AuditService auditService;

    public ServiceItemController(ServiceItemService itemService, ShopService shopService, AuditService auditService) {
        this.itemService = itemService;
        this.shopService = shopService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Long shopId = shopService.requirePrimaryShop().getId();
        return itemService.listAll(shopId).stream().map(this::view).toList();
    }

    @GetMapping("/templates")
    public Map<String, List<Map<String, Object>>> templates() {
        return itemService.templates();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long shopId = shopService.requirePrimaryShop().getId();
        ServiceItem item = itemService.create(shopId, body);
        auditService.audit(currentUser(), "SERVICE_ITEM_CREATE", "item:" + item.getId(), item.getName());
        return view(item);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ServiceItem item = itemService.update(id, body);
        auditService.audit(currentUser(), "SERVICE_ITEM_UPDATE", "item:" + id, item.getName());
        return view(item);
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.get("status"));
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw BusinessException.badRequest("状态只能是 ACTIVE 或 INACTIVE");
        }
        itemService.changeStatus(id, status);
        auditService.audit(currentUser(), "SERVICE_ITEM_STATUS", "item:" + id, status);
        return view(itemService.require(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        itemService.delete(id);
        auditService.audit(currentUser(), "SERVICE_ITEM_DELETE", "item:" + id, null);
        return Map.of("ok", true);
    }

    private Map<String, Object> view(ServiceItem item) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("name", item.getName());
        m.put("capacityPerUnit", item.getCapacityPerUnit());
        m.put("unitCount", item.getUnitCount());
        m.put("durationMinutes", item.getDurationMinutes());
        m.put("price", item.getPrice() == null ? null : item.getPrice().toPlainString());
        m.put("advanceDays", item.getAdvanceDays());
        m.put("cancelPolicy", item.getCancelPolicy());
        m.put("sortOrder", item.getSortOrder());
        m.put("status", item.getStatus());
        return m;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
