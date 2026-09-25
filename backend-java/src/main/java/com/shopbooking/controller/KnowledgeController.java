package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.KnowledgeItem;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.KnowledgeService;
import com.shopbooking.service.ShopService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识库维护（仅老板）：三种录入——模板导入、手动添加、工单一键回流 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final ShopService shopService;
    private final AuditService auditService;

    public KnowledgeController(KnowledgeService knowledgeService, ShopService shopService, AuditService auditService) {
        this.knowledgeService = knowledgeService;
        this.shopService = shopService;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        Long shopId = shopService.requirePrimaryShop().getId();
        List<KnowledgeItem> items = knowledgeService.list(shopId, keyword, status);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("list", items.stream().map(this::view).toList());
        return result;
    }

    @GetMapping("/templates")
    public Map<String, List<Map<String, String>>> templates() {
        return knowledgeService.templates();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long shopId = shopService.requirePrimaryShop().getId();
        KnowledgeItem item = knowledgeService.create(shopId, body);
        auditService.audit(currentUser(), "KNOWLEDGE_CREATE", "knowledge:" + item.getId(), item.getQuestion());
        return view(item);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        KnowledgeItem item = knowledgeService.update(id, body);
        auditService.audit(currentUser(), "KNOWLEDGE_UPDATE", "knowledge:" + id, null);
        return view(item);
    }

    @PostMapping("/{id}/status")
    public Map<String, Object> toggleStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        knowledgeService.toggleStatus(id, String.valueOf(body.get("status")));
        return view(knowledgeService.require(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        auditService.audit(currentUser(), "KNOWLEDGE_DELETE", "knowledge:" + id, null);
        return Map.of("ok", true);
    }

    /** 模板批量导入：勾选的问题入库，答案可再改 */
    @PostMapping("/import")
    public Map<String, Object> importTemplates(@RequestBody Map<String, Object> body) {
        Long shopId = shopService.requirePrimaryShop().getId();
        String industry = String.valueOf(body.get("industry"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> selected = (List<Map<String, String>>) body.get("selected");
        if (selected == null || selected.isEmpty()) {
            throw BusinessException.badRequest("请至少勾选一条模板");
        }
        int count = knowledgeService.importTemplates(shopId, industry, selected);
        auditService.audit(currentUser(), "KNOWLEDGE_IMPORT", "industry:" + industry, count + " 条");
        return Map.of("imported", count);
    }

    private Map<String, Object> view(KnowledgeItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("question", item.getQuestion());
        m.put("answer", item.getAnswer());
        m.put("keywords", item.getKeywords());
        m.put("category", item.getCategory());
        m.put("hitCount", item.getHitCount());
        m.put("status", item.getStatus());
        m.put("source", item.getSource());
        m.put("escalationId", item.getEscalationId());
        return m;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
