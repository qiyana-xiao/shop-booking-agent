package com.shopbooking.service;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.AgentLoop;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.AgentStep;
import com.shopbooking.entity.Booking;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Escalation;
import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.mapper.UserMapper;
import com.shopbooking.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对话入口编排：限流 → 会话 → Agent 循环 → 降级兜底 → 持久化 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final BookingService bookingService;
    private final BookingMapper bookingMapper;
    private final KnowledgeService knowledgeService;
    private final EscalationService escalationService;
    private final ShopService shopService;
    private final RateLimitService rateLimitService;
    private final UserMapper userMapper;
    private final DeepSeekService deepSeekService;
    private final AppProperties props;

    public ChatService(AgentLoop agentLoop, ConversationService conversationService,
                       BookingService bookingService, BookingMapper bookingMapper,
                       KnowledgeService knowledgeService, EscalationService escalationService,
                       ShopService shopService, RateLimitService rateLimitService,
                       UserMapper userMapper, DeepSeekService deepSeekService, AppProperties props) {
        this.agentLoop = agentLoop;
        this.conversationService = conversationService;
        this.bookingService = bookingService;
        this.bookingMapper = bookingMapper;
        this.knowledgeService = knowledgeService;
        this.escalationService = escalationService;
        this.shopService = shopService;
        this.rateLimitService = rateLimitService;
        this.userMapper = userMapper;
        this.deepSeekService = deepSeekService;
        this.props = props;
    }

    public Map<String, Object> chat(String sessionKey, String message, Long userId, String ip) {
        if (message == null || message.isBlank()) {
            throw com.shopbooking.common.BusinessException.badRequest("消息不能为空");
        }
        message = message.trim();
        if (message.length() > props.getChat().getMaxMessageLength()) {
            throw com.shopbooking.common.BusinessException.badRequest(
                    "单条消息不能超过 " + props.getChat().getMaxMessageLength() + " 字");
        }
        if (sessionKey == null || sessionKey.isBlank()) {
            sessionKey = "anonymous-" + (ip == null ? "unknown" : ip);
        }
        if (!rateLimitService.allowChat(sessionKey, ip, props.getChat().getRateLimitPerMinute())) {
            throw com.shopbooking.common.BusinessException.tooManyRequests(
                    "发送太频繁啦，休息一下，一分钟后再试");
        }

        Shop shop = shopService.primaryShop();
        if (shop == null || !Boolean.TRUE.equals(shop.getSetupCompleted())) {
            Map<String, Object> early = new LinkedHashMap<>();
            early.put("reply", "抱歉，店家还没有完成开店配置，暂时无法接待预约，请稍后再来～");
            early.put("conversationId", null);
            early.put("trace", List.of());
            early.put("degraded", true);
            return early;
        }

        Conversation conversation = conversationService.getOrCreate(shop.getId(), sessionKey, userId);
        conversationService.appendMessage(conversation.getId(), "user", message, null);

        // 人工接管中（存在未解决工单）：顾客消息只落库转给店家，AI 暂停应答，不调 DeepSeek
        Escalation active = escalationService.findActive(conversation.getId());
        if (active != null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("reply", "已收到～店家正在处理，人工客服会尽快回复您。");
            resp.put("conversationId", conversation.getId());
            resp.put("degraded", false);
            resp.put("trace", List.of());
            resp.put("escalated", true);
            resp.put("humanMode", true);
            if (shop.getPhone() != null && !shop.getPhone().isBlank()) {
                resp.put("shopPhone", shop.getPhone());
            }
            resp.put("booking", latestBookingView(sessionKey));
            return resp;
        }

        AgentContext ctx = new AgentContext(conversation.getId(), shop.getId(), sessionKey, userId);
        String reply;
        boolean escalated = false;
        long tokens = 0;
        boolean degraded = false;

        try {
            AgentLoop.AgentRunResult result = agentLoop.run(ctx, conversation, message);
            reply = result.reply();
            escalated = result.escalated();
            tokens = result.tokensUsed();
        } catch (DeepSeekService.DeepSeekUnavailableException e) {
            log.warn("DeepSeek 不可用，进入降级路径：{}", e.getMessage());
            degraded = true;
            reply = degradedReply(ctx, message);
        }
        // 对话窗是纯文本渲染，剥掉模型可能输出的 Markdown 符号（**、#、`）
        reply = com.shopbooking.common.MarkdownCleaner.strip(reply);

        // 轨迹与消息落库
        conversationService.saveSteps(conversation.getId(), ctx.getSteps());
        conversationService.appendMessage(conversation.getId(), "assistant", reply, null);
        for (AgentStep step : ctx.getSteps()) {
            conversationService.appendMessage(conversation.getId(), "tool",
                    "调用工具 " + step.getToolName() + "（第 " + step.getTurn() + " 轮，"
                            + step.getDurationMs() + "ms" + (Boolean.TRUE.equals(step.getHitIdempotent()) ? "，命中幂等" : "")
                            + "）", step.getToolName());
        }
        if (tokens > 0) {
            conversationService.addTokens(conversation.getId(), tokens);
        }
        Conversation fresh = conversationService.require(conversation.getId());
        conversationService.compressSummaryIfNeeded(fresh, deepSeekService);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", reply);
        response.put("conversationId", conversation.getId());
        response.put("degraded", degraded);
        response.put("trace", traceViews(ctx.getSteps()));
        response.put("escalated", escalated);
        response.put("humanMode", false);
        response.put("booking", latestBookingView(sessionKey));
        return response;
    }

    /**
     * 降级路径：FAQ 关键词兜底 + 直接转人工，绝不编造档期。
     * DeepSeek 超时/5xx 重试失败、密钥未配置时走这里，系统不白屏。
     */
    private String degradedReply(AgentContext ctx, String message) {
        List<Map<String, Object>> matched = knowledgeService.search(ctx.getShopId(), message);
        StringBuilder sb = new StringBuilder("抱歉，AI 客服这会儿有点忙，暂时处理不了。\n");
        if (!matched.isEmpty()) {
            sb.append("帮您查了一下常见问题：\n");
            for (Map<String, Object> m : matched) {
                sb.append("【").append(m.get("question")).append("】\n").append(m.get("answer")).append("\n\n");
            }
        }
        escalationService.create(ctx.getShopId(), ctx.getConversationId(),
                "AI 服务降级，自动转人工", escalationService.buildSummaryFromConversation(ctx.getConversationId()), null);
        sb.append("已为您转接人工客服，店家看到后会尽快联系您，您也可以直接拨打电话咨询。");
        return sb.toString();
    }

    private List<Map<String, Object>> traceViews(List<AgentStep> steps) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (AgentStep step : steps) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("turn", step.getTurn());
            v.put("tool", step.getToolName());
            v.put("args", step.getArgs());
            v.put("result", step.getResult());
            v.put("durationMs", step.getDurationMs());
            v.put("hitIdempotent", Boolean.TRUE.equals(step.getHitIdempotent()));
            v.put("success", Boolean.TRUE.equals(step.getSuccess()));
            views.add(v);
        }
        return views;
    }

    /** 本轮涉及最新预约（对话气泡中的预约成功卡片） */
    private Map<String, Object> latestBookingView(String sessionKey) {
        Booking latest = bookingMapper.findLatestActiveBySession(sessionKey);
        return latest == null ? null : bookingService.view(latest);
    }

    public Map<String, Object> userBrief(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("role", user.getRole());
        return m;
    }
}
