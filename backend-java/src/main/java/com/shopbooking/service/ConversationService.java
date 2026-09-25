package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.common.PageResult;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.AgentStep;
import com.shopbooking.entity.ChatMessage;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Escalation;
import com.shopbooking.mapper.AgentStepMapper;
import com.shopbooking.mapper.ChatMessageMapper;
import com.shopbooking.mapper.ConversationMapper;
import com.shopbooking.mapper.EscalationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话与消息：MySQL 持久化为主，Redis 做短期记忆缓存（2 小时，可重建） */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentStepMapper stepMapper;
    private final EscalationMapper escalationMapper;
    private final SafeRedisService redis;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper messageMapper,
                               AgentStepMapper stepMapper, EscalationMapper escalationMapper,
                               SafeRedisService redis, AppProperties props, ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.stepMapper = stepMapper;
        this.escalationMapper = escalationMapper;
        this.redis = redis;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public Conversation getOrCreate(Long shopId, String sessionKey, Long userId) {
        Conversation existing = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getShopId, shopId)
                        .eq(Conversation::getSessionKey, sessionKey)
                        .eq(Conversation::getStatus, "OPEN")
                        .orderByDesc(Conversation::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (existing != null) {
            if (userId != null && existing.getUserId() == null) {
                existing.setUserId(userId);
                conversationMapper.updateById(existing);
            }
            return existing;
        }
        Conversation c = new Conversation();
        c.setShopId(shopId);
        c.setSessionKey(sessionKey);
        c.setUserId(userId);
        c.setStatus("OPEN");
        c.setTurnCount(0);
        c.setTotalTokens(0L);
        c.setLastMessageAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }

    public Conversation require(Long id) {
        Conversation c = conversationMapper.selectById(id);
        if (c == null) {
            throw com.shopbooking.common.BusinessException.notFound("会话不存在");
        }
        return c;
    }

    public void appendMessage(Long conversationId, String role, String content, String toolName) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setToolName(toolName);
        messageMapper.insert(m);
        redis.delete(msgCacheKey(conversationId));
        Conversation c = conversationMapper.selectById(conversationId);
        if (c != null) {
            c.setLastMessageAt(LocalDateTime.now());
            if (ChatMessage.ROLE_USER.equals(role)) {
                c.setTurnCount(c.getTurnCount() == null ? 1 : c.getTurnCount() + 1);
            }
            conversationMapper.updateById(c);
        }
    }

    /** 最近 N 条对话消息（user/assistant/human），Redis 读缓存 + 数据库兜底 */
    public List<ChatMessage> recentMessages(Long conversationId) {
        String cacheKey = msgCacheKey(conversationId);
        String cached = redis.get(cacheKey);
        if (cached != null) {
            try {
                List<ChatMessage> list = objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
                if (list != null) {
                    return list;
                }
            } catch (Exception ignored) {
            }
        }
        List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .in(ChatMessage::getRole, List.of(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT, ChatMessage.ROLE_HUMAN))
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + props.getChat().getMaxHistoryMessages()));
        List<ChatMessage> chronological = new ArrayList<>(messages);
        java.util.Collections.reverse(chronological);
        try {
            redis.set(cacheKey, objectMapper.writeValueAsString(chronological), Duration.ofHours(2));
        } catch (Exception ignored) {
        }
        return chronological;
    }

    public void addTokens(Long conversationId, long tokens) {
        Conversation c = conversationMapper.selectById(conversationId);
        if (c != null) {
            c.setTotalTokens((c.getTotalTokens() == null ? 0 : c.getTotalTokens()) + tokens);
            conversationMapper.updateById(c);
        }
    }

    /** 滚动摘要：超出阈值轮次后，把早期消息压成摘要，避免上下文爆炸 */
    public void compressSummaryIfNeeded(Conversation conversation, DeepSeekService deepSeek) {
        int trigger = props.getChat().getSummaryTriggerTurns();
        Integer turnCount = conversation.getTurnCount();
        if (turnCount == null || turnCount < trigger || turnCount % 8 != 0) {
            return;
        }
        try {
            List<ChatMessage> all = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getConversationId, conversation.getId())
                    .in(ChatMessage::getRole, List.of(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT))
                    .orderByAsc(ChatMessage::getId));
            int keep = props.getChat().getMaxHistoryMessages();
            if (all.size() <= keep) {
                return;
            }
            List<ChatMessage> dropped = all.subList(0, all.size() - keep);
            String previous = conversation.getSummary();
            String summary = deepSeek.summarize(previous, dropped);
            if (summary != null && !summary.isBlank()) {
                conversation.setSummary(summary);
                conversationMapper.updateById(conversation);
                redis.set("shop-booking:conv:" + conversation.getId() + ":summary", summary, Duration.ofHours(2));
                log.info("会话 {} 滚动摘要已更新（{} 轮）", conversation.getId(), turnCount);
            }
        } catch (Exception e) {
            log.warn("滚动摘要生成失败，下次再试：{}", e.getMessage());
        }
    }

    public void saveSteps(Long conversationId, List<AgentStep> steps) {
        for (AgentStep step : steps) {
            step.setConversationId(conversationId);
            stepMapper.insert(step);
        }
    }

    public List<ChatMessage> messages(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId));
    }

    public List<AgentStep> steps(Long conversationId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getConversationId, conversationId)
                .orderByAsc(AgentStep::getId));
    }

    public List<Map<String, Object>> mine(Long shopId, String sessionKey, Long userId) {
        LambdaQueryWrapper<Conversation> q = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getShopId, shopId)
                .eq(Conversation::getSessionKey, sessionKey)
                .orderByDesc(Conversation::getId)
                .last("LIMIT 20");
        return conversationMapper.selectList(q).stream().map(this::view).toList();
    }

    public PageResult<Map<String, Object>> listForShop(Long shopId, int page, int size) {
        Page<Conversation> p = conversationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getShopId, shopId)
                        .orderByDesc(Conversation::getId));
        List<Map<String, Object>> list = p.getRecords().stream().map(this::view).toList();
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    public Map<String, Object> view(Conversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("sessionKey", c.getSessionKey());
        m.put("status", c.getStatus());
        m.put("turnCount", c.getTurnCount());
        m.put("totalTokens", c.getTotalTokens());
        m.put("hasSummary", c.getSummary() != null && !c.getSummary().isBlank());
        m.put("lastMessageAt", c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().toString());
        Long escalated = escalationMapper.selectCount(new LambdaQueryWrapper<Escalation>()
                .eq(Escalation::getConversationId, c.getId()));
        m.put("escalated", escalated > 0);
        return m;
    }

    private String msgCacheKey(Long conversationId) {
        return "shop-booking:conv:" + conversationId + ":msgs";
    }
}
