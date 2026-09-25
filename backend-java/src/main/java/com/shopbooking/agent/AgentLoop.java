package com.shopbooking.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Shop;
import com.shopbooking.service.ConversationService;
import com.shopbooking.service.DeepSeekService;
import com.shopbooking.service.EscalationService;
import com.shopbooking.service.ShopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环（整个项目的心脏）：
 * 模型输出"要调什么工具"，执行永远发生在 JVM 里；模型碰不到数据库。
 * 结束条件：模型不再要工具（正常回答）/ 触发转人工 / 超轮次或超 token 预算熔断。
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    public record AgentRunResult(String reply, boolean escalated, long tokensUsed) {
    }

    private final ToolRegistry toolRegistry;
    private final DeepSeekService deepSeek;
    private final ConversationService conversationService;
    private final EscalationService escalationService;
    private final SystemPromptBuilder promptBuilder;
    private final ShopService shopService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public AgentLoop(ToolRegistry toolRegistry, DeepSeekService deepSeek,
                     ConversationService conversationService, EscalationService escalationService,
                     SystemPromptBuilder promptBuilder, ShopService shopService,
                     AppProperties props, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.deepSeek = deepSeek;
        this.conversationService = conversationService;
        this.escalationService = escalationService;
        this.promptBuilder = promptBuilder;
        this.shopService = shopService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public AgentRunResult run(AgentContext ctx, Conversation conversation, String userMessage) {
        List<Map<String, Object>> messages = assembleMessages(ctx, conversation, userMessage);
        Map<String, Integer> toolFailures = new LinkedHashMap<>();
        long tokensUsed = 0;
        boolean escalated = false;

        for (int turn = 1; turn <= props.getChat().getMaxTurns(); turn++) {
            DeepSeekService.ChatResponse resp = deepSeek.chat(messages, toolRegistry.definitions());
            tokensUsed += resp.totalTokens();
            if (tokensUsed > props.getChat().getTokenBudget()) {
                return circuitBreak(ctx, "EXCEED_TOKEN_BUDGET", tokensUsed);
            }

            if (resp.toolCalls() == null || resp.toolCalls().isEmpty()) {
                String reply = resp.content() == null || resp.content().isBlank()
                        ? "抱歉，我刚才走神了，您再说一遍需求好吗？" : resp.content();
                return new AgentRunResult(reply, escalated, tokensUsed);
            }

            // 模型要调工具：把 assistant 的 tool_calls 消息回喂
            messages.add(assistantToolCallMessage(resp));

            for (DeepSeekService.ToolCall call : resp.toolCalls()) {
                ToolResult result = toolRegistry.invoke(call.name(), call.arguments(), ctx, turn);
                messages.add(toolMessage(call.id(), result));
                if ("escalate_to_human".equals(call.name()) && result.isSuccess()) {
                    escalated = true;
                }
                // 二次失败熔断：同一工具连续失败两次，转人工
                if (!result.isSuccess()) {
                    int fails = toolFailures.merge(call.name(), 1, Integer::sum);
                    if (fails >= 2) {
                        log.warn("工具 {} 连续失败 {} 次，熔断转人工", call.name(), fails);
                        return circuitBreak(ctx, "TOOL_REPEATED_FAILURE:" + call.name(), tokensUsed);
                    }
                } else {
                    toolFailures.remove(call.name());
                }
            }
            if (escalated) {
                // 已生成工单，让模型再收个尾即可；若下一轮仍不停，超轮次熔断兜底
                log.info("会话 {} 已转人工", ctx.getConversationId());
            }
        }
        return circuitBreak(ctx, "EXCEED_MAX_TURNS", tokensUsed);
    }

    /** 熔断转人工：绝不带着不确定状态继续回答 */
    private AgentRunResult circuitBreak(AgentContext ctx, String reason, long tokensUsed) {
        String summary = escalationService.buildSummaryFromConversation(ctx.getConversationId());
        escalationService.create(ctx.getShopId(), ctx.getConversationId(),
                "系统熔断：" + reason, summary, null);
        String reply = "这个问题有点复杂，我拿不太准，已经为您转接人工客服了，店家会尽快联系您"
                + "（您的预约记录和刚才的沟通内容都已一并转交，不用重复说明）。";
        return new AgentRunResult(reply, true, tokensUsed);
    }

    private List<Map<String, Object>> assembleMessages(AgentContext ctx, Conversation conversation, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Shop shop = shopService.requirePrimaryShop();
        messages.add(Map.of("role", "system",
                "content", promptBuilder.build(shop, conversation.getSummary())));
        for (com.shopbooking.entity.ChatMessage m : conversationService.recentMessages(conversation.getId())) {
            if (com.shopbooking.entity.ChatMessage.ROLE_HUMAN.equals(m.getRole())) {
                // 店家人工回复以 assistant 身份回喂模型，AI 恢复后能接上人工聊过的上下文
                messages.add(Map.of("role", "assistant",
                        "content", "【店家人工回复（转述给顾客的）】" + m.getContent()));
            } else {
                messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content",
                "<customer_message>\n" + userMessage + "\n</customer_message>"));
        return messages;
    }

    private Map<String, Object> assistantToolCallMessage(DeepSeekService.ChatResponse resp) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (DeepSeekService.ToolCall call : resp.toolCalls()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", call.name());
            try {
                fn.put("arguments", objectMapper.writeValueAsString(call.arguments() == null ? Map.of() : call.arguments()));
            } catch (Exception e) {
                fn.put("arguments", "{}");
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", call.id());
            c.put("type", "function");
            c.put("function", fn);
            calls.add(c);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", resp.content());
        message.put("tool_calls", calls);
        return message;
    }

    private Map<String, Object> toolMessage(String toolCallId, ToolResult result) {
        String content;
        try {
            content = objectMapper.writeValueAsString(result.getData());
        } catch (Exception e) {
            content = "{\"success\":false,\"error\":\"结果序列化失败\"}";
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content);
        return message;
    }
}
