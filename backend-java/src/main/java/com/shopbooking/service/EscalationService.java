package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shopbooking.common.BusinessException;
import com.shopbooking.common.PageResult;
import com.shopbooking.entity.ChatMessage;
import com.shopbooking.entity.Escalation;
import com.shopbooking.mapper.ChatMessageMapper;
import com.shopbooking.mapper.EscalationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 转人工工单：携带会话摘要 + 已收集字段，店员接手时顾客不用重讲 */
@Service
public class EscalationService {

    private final EscalationMapper escalationMapper;
    private final ChatMessageMapper messageMapper;
    private final KnowledgeService knowledgeService;

    public EscalationService(EscalationMapper escalationMapper, ChatMessageMapper messageMapper,
                             KnowledgeService knowledgeService) {
        this.escalationMapper = escalationMapper;
        this.messageMapper = messageMapper;
        this.knowledgeService = knowledgeService;
    }

    @Transactional
    public Escalation create(Long shopId, Long conversationId, String reason, String summary, String collectedFields) {
        if (reason == null || reason.isBlank()) {
            reason = "AI 无法处理";
        }
        // 防重：同一会话已有未解决工单时不重复建单（模型收尾轮可能再次调用 escalate）
        if (conversationId != null) {
            Escalation existing = findActive(conversationId);
            if (existing != null) {
                return existing;
            }
        }
        Escalation e = new Escalation();
        e.setShopId(shopId);
        e.setConversationId(conversationId);
        e.setReason(reason.length() > 200 ? reason.substring(0, 200) : reason);
        e.setSummary(summary);
        e.setCollectedFields(collectedFields);
        e.setStatus(Escalation.STATUS_OPEN);
        escalationMapper.insert(e);
        return e;
    }

    /** 人工接管判定：会话存在未解决的工单（OPEN/PROCESSING）即处于人工模式，AI 暂停应答 */
    public Escalation findActive(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return escalationMapper.selectList(new LambdaQueryWrapper<Escalation>()
                        .eq(Escalation::getConversationId, conversationId)
                        .in(Escalation::getStatus, List.of(Escalation.STATUS_OPEN, Escalation.STATUS_PROCESSING))
                        .orderByDesc(Escalation::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 店员/老板回复顾客：写入会话（human 角色，顾客端显示为店家气泡），工单自动进入处理中 */
    @Transactional
    public Map<String, Object> reply(Long id, Long handlerId, String content, String handlerName) {
        if (content == null || content.isBlank()) {
            throw BusinessException.badRequest("回复内容不能为空");
        }
        if (content.length() > 500) {
            throw BusinessException.badRequest("回复内容不能超过 500 字");
        }
        Escalation e = require(id);
        if (Escalation.STATUS_RESOLVED.equals(e.getStatus())) {
            throw BusinessException.badRequest("工单已处理完成，如需再联系顾客请发起新对话");
        }
        if (Escalation.STATUS_OPEN.equals(e.getStatus())) {
            e.setStatus(Escalation.STATUS_PROCESSING);
        }
        if (handlerId != null) {
            e.setHandlerId(handlerId);
        }
        escalationMapper.updateById(e);
        if (e.getConversationId() != null) {
            messageMapper.insert(humanMessage(e.getConversationId(), content, handlerName));
        }
        return view(e);
    }

    private com.shopbooking.entity.ChatMessage humanMessage(Long conversationId, String content, String handlerName) {
        com.shopbooking.entity.ChatMessage m = new com.shopbooking.entity.ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(com.shopbooking.entity.ChatMessage.ROLE_HUMAN);
        m.setContent((handlerName == null || handlerName.isBlank() ? "" : "【" + handlerName + "】") + content.trim());
        return m;
    }

    /** 从最近消息自动生成会话摘要（不要求模型参与，DeepSeek 不可用时也能转人工） */
    public String buildSummaryFromConversation(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT 12"));
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.getRole())) {
                sb.append("顾客：").append(truncate(m.getContent(), 100)).append("\n");
            } else if ("assistant".equals(m.getRole())) {
                sb.append("AI：").append(truncate(m.getContent(), 80)).append("\n");
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public PageResult<Map<String, Object>> list(Long shopId, String status, int page, int size) {
        LambdaQueryWrapper<Escalation> q = new LambdaQueryWrapper<Escalation>()
                .eq(Escalation::getShopId, shopId)
                .orderByDesc(Escalation::getId);
        if (status != null && !status.isBlank()) {
            q.eq(Escalation::getStatus, status);
        }
        Page<Escalation> p = escalationMapper.selectPage(new Page<>(page, size), q);
        List<Map<String, Object>> list = p.getRecords().stream().map(this::view).toList();
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    public Map<String, Object> view(Escalation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("conversationId", e.getConversationId());
        m.put("reason", e.getReason());
        m.put("summary", e.getSummary());
        m.put("collectedFields", e.getCollectedFields());
        m.put("status", e.getStatus());
        m.put("handlerId", e.getHandlerId());
        m.put("resolutionNote", e.getResolutionNote());
        m.put("resolvedAt", e.getResolvedAt() == null ? null : e.getResolvedAt().toString());
        m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return m;
    }

    @Transactional
    public Map<String, Object> claim(Long id, Long handlerId) {
        Escalation e = require(id);
        if (Escalation.STATUS_RESOLVED.equals(e.getStatus())) {
            throw BusinessException.badRequest("工单已处理完成");
        }
        e.setStatus(Escalation.STATUS_PROCESSING);
        e.setHandlerId(handlerId);
        escalationMapper.updateById(e);
        return view(e);
    }

    @Transactional
    public Map<String, Object> resolve(Long id, Long handlerId, String note) {
        Escalation e = require(id);
        e.setStatus(Escalation.STATUS_RESOLVED);
        e.setHandlerId(handlerId);
        e.setResolutionNote(note);
        e.setResolvedAt(java.time.LocalDateTime.now());
        escalationMapper.updateById(e);
        return view(e);
    }

    /** 工单一键回流：老板答过一次的问题设为标准答案，下次 AI 就会答 */
    @Transactional
    public Map<String, Object> adoptAsKnowledge(Long id, String question, String answer) {
        Escalation e = require(id);
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            throw BusinessException.badRequest("问题与答案都必填");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("answer", answer);
        body.put("source", "FEEDBACK");
        body.put("category", "工单回流");
        var knowledge = knowledgeService.create(e.getShopId(), body);
        knowledgeService.linkEscalation(knowledge.getId(), e.getId());
        if (!Escalation.STATUS_RESOLVED.equals(e.getStatus())) {
            e.setStatus(Escalation.STATUS_RESOLVED);
            e.setResolutionNote("已采纳为标准答案（知识库 #" + knowledge.getId() + "）");
            e.setResolvedAt(java.time.LocalDateTime.now());
            escalationMapper.updateById(e);
        }
        Map<String, Object> result = view(e);
        result.put("knowledgeId", knowledge.getId());
        return result;
    }

    public Escalation require(Long id) {
        Escalation e = escalationMapper.selectById(id);
        if (e == null) {
            throw BusinessException.notFound("工单不存在");
        }
        return e;
    }
}
