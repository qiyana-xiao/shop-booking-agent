package com.shopbooking.controller;

import com.shopbooking.common.BusinessException;
import com.shopbooking.common.SessionKeys;
import com.shopbooking.dto.ChatRequest;
import com.shopbooking.entity.AgentStep;
import com.shopbooking.entity.ChatMessage;
import com.shopbooking.entity.Conversation;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.ChatService;
import com.shopbooking.service.ConversationService;
import com.shopbooking.service.ShopService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 顾客对话入口：游客以 sessionKey 标识，登录顾客由后端强制使用账号专属会话键（user-{id}），各账号互不可见 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final ShopService shopService;
    private final com.shopbooking.service.EscalationService escalationService;

    public ChatController(ChatService chatService, ConversationService conversationService,
                          ShopService shopService,
                          com.shopbooking.service.EscalationService escalationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.shopService = shopService;
        this.escalationService = escalationService;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req, HttpServletRequest request) {
        String sessionKey = SessionKeys.extract(request, req.sessionKey());
        if (sessionKey == null) {
            throw BusinessException.badRequest("缺少会话标识，请刷新页面后重试");
        }
        SecurityUser user = currentUser();
        Long userId = user == null ? null : user.getId();
        return chatService.chat(sessionKey, req.message(), userId, clientIp(request));
    }

    /** 历史回放：游客换设备/刷新页面后取回最近一个会话的消息与 Agent 轨迹 */
    @GetMapping("/api/chat/history")
    public Map<String, Object> history(@RequestParam(required = false) String sessionKey,
                                       @RequestParam(required = false) Long conversationId,
                                       HttpServletRequest request) {
        String key = SessionKeys.extract(request, sessionKey);
        if (key == null) {
            throw BusinessException.badRequest("缺少会话标识");
        }
        Long shopId;
        try {
            shopId = shopService.requirePrimaryShop().getId();
        } catch (BusinessException e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("conversationId", null);
            empty.put("humanMode", false);
            empty.put("messages", List.of());
            empty.put("steps", List.of());
            return empty;
        }
        Conversation conversation;
        if (conversationId != null) {
            conversation = conversationService.require(conversationId);
            if (!key.equals(conversation.getSessionKey())) {
                throw BusinessException.forbidden("无权查看该会话");
            }
        } else {
            List<Map<String, Object>> mine = conversationService.mine(shopId, key, null);
            if (mine.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("conversationId", null);
                empty.put("humanMode", false);
                empty.put("messages", List.of());
                empty.put("steps", List.of());
                return empty;
            }
            conversation = conversationService.require((Long) mine.get(0).get("id"));
        }
        List<ChatMessage> messages = conversationService.messages(conversation.getId());
        List<AgentStep> steps = conversationService.steps(conversation.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversation.getId());
        result.put("humanMode", escalationService.findActive(conversation.getId()) != null);
        // tool 消息是内部执行轨迹，不暴露给对话界面
        result.put("messages", messages.stream()
                .filter(m -> !"tool".equals(m.getRole()))
                .map(this::messageView).toList());
        result.put("steps", steps.stream().map(this::stepView).toList());
        return result;
    }

    private Map<String, Object> messageView(ChatMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("role", m.getRole());
        v.put("content", m.getContent());
        v.put("toolName", m.getToolName());
        return v;
    }

    private Map<String, Object> stepView(AgentStep s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("turn", s.getTurn());
        v.put("tool", s.getToolName());
        v.put("args", s.getArgs());
        v.put("result", s.getResult());
        v.put("durationMs", s.getDurationMs());
        v.put("success", Boolean.TRUE.equals(s.getSuccess()));
        v.put("hitIdempotent", Boolean.TRUE.equals(s.getHitIdempotent()));
        return v;
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
