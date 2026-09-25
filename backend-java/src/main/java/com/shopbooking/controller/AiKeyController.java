package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AiKeyService;
import com.shopbooking.service.AuditService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 客服密钥管理（仅老板）：密钥以 AES-GCM 密文保存到 app_settings 表，
 * 界面保存后立即生效，无需重启；删除后回退到 .env 中配置的密钥（如有）。
 */
@RestController
@RequestMapping("/api/settings/ai-key")
public class AiKeyController {

    private final AiKeyService aiKeyService;
    private final AuditService auditService;

    public AiKeyController(AiKeyService aiKeyService, AuditService auditService) {
        this.aiKeyService = aiKeyService;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> status() {
        return statusBody();
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody Map<String, String> body) {
        String key = body.get("key");
        if (key == null || key.isBlank()) {
            throw BusinessException.badRequest("密钥不能为空");
        }
        aiKeyService.saveKey(key);
        auditService.audit(currentUser(), "AI_KEY_UPDATE", "ai_key", "老板通过界面更新 AI 客服密钥（已加密保存）");
        return statusBody();
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        aiKeyService.clearKey();
        auditService.audit(currentUser(), "AI_KEY_CLEAR", "ai_key", "老板清除界面配置的 AI 客服密钥");
        return statusBody();
    }

    private Map<String, Object> statusBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", aiKeyService.configured());
        m.put("masked", aiKeyService.maskedKey() == null ? "" : aiKeyService.maskedKey());
        m.put("source", aiKeyService.source());
        return m;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
