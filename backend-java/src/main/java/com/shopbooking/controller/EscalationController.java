package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.common.PageResult;
import com.shopbooking.entity.ChatMessage;
import com.shopbooking.entity.Escalation;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.ConversationService;
import com.shopbooking.service.EscalationService;
import com.shopbooking.service.ShopService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 转人工工单（老板/店员）：接手时工单已带完整会话摘要，顾客不用重讲一遍 */
@RestController
@RequestMapping("/api/escalations")
public class EscalationController {

    private final EscalationService escalationService;
    private final ConversationService conversationService;
    private final ShopService shopService;
    private final AuditService auditService;

    public EscalationController(EscalationService escalationService,
                                ConversationService conversationService,
                                ShopService shopService, AuditService auditService) {
        this.escalationService = escalationService;
        this.conversationService = conversationService;
        this.shopService = shopService;
        this.auditService = auditService;
    }

    @GetMapping
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Long shopId = shopService.requirePrimaryShop().getId();
        if (size < 1 || size > 100) {
            throw BusinessException.badRequest("每页条数需在 1-100 之间");
        }
        return escalationService.list(shopId, status, Math.max(1, page), size);
    }

    /** 工单详情：含原始对话消息，店员接手前先看上下文 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Escalation e = escalationService.require(id);
        Map<String, Object> result = escalationService.view(e);
        if (e.getConversationId() != null) {
            List<ChatMessage> messages = conversationService.messages(e.getConversationId());
            // 只给店家看真实对话（顾客/AI/店家），内部工具轨迹不展示
            result.put("messages", messages.stream()
                    .filter(m -> !"tool".equals(m.getRole()))
                    .map(m -> {
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("role", m.getRole());
                        v.put("content", m.getContent());
                        return v;
                    }).toList());
        }
        return result;
    }

    @PostMapping("/{id}/claim")
    public Map<String, Object> claim(@PathVariable Long id) {
        SecurityUser user = currentUser();
        Map<String, Object> result = escalationService.claim(id, user == null ? null : user.getId());
        auditService.audit(user, "ESCALATION_CLAIM", "escalation:" + id, null);
        return result;
    }

    /** 店员/老板回复顾客：消息直接出现在顾客对话窗（店家气泡），工单自动进入处理中 */
    @PostMapping("/{id}/reply")
    public Map<String, Object> reply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SecurityUser user = currentUser();
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        Map<String, Object> result = escalationService.reply(id,
                user == null ? null : user.getId(),
                content,
                user == null ? null : user.getUsername());
        auditService.audit(user, "ESCALATION_REPLY", "escalation:" + id, null);
        return result;
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        SecurityUser user = currentUser();
        String note = body == null ? null : String.valueOf(body.get("note"));
        Map<String, Object> result = escalationService.resolve(id, user == null ? null : user.getId(), note);
        auditService.audit(user, "ESCALATION_RESOLVE", "escalation:" + id, note);
        return result;
    }

    /** 工单一键回流：老板答过一次的问题设为标准答案，下次 AI 就会答 */
    @PostMapping("/{id}/adopt-as-knowledge")
    public Map<String, Object> adoptAsKnowledge(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String question = body.get("question") == null ? null : String.valueOf(body.get("question"));
        String answer = body.get("answer") == null ? null : String.valueOf(body.get("answer"));
        Map<String, Object> result = escalationService.adoptAsKnowledge(id, question, answer);
        auditService.audit(currentUser(), "KNOWLEDGE_FEEDBACK", "escalation:" + id,
                "回流为知识库 #" + result.get("knowledgeId"));
        return result;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
