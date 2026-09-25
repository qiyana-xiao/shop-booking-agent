package com.shopbooking.agent;

import com.shopbooking.entity.AgentStep;

import java.util.ArrayList;
import java.util.List;

/** 一次 Agent 运行的上下文：贯穿工具调用与轨迹记录 */
public class AgentContext {

    private final Long conversationId;
    private final Long shopId;
    private final String sessionKey;
    private final Long userId;
    private final List<AgentStep> steps = new ArrayList<>();

    public AgentContext(Long conversationId, Long shopId, String sessionKey, Long userId) {
        this.conversationId = conversationId;
        this.shopId = shopId;
        this.sessionKey = sessionKey;
        this.userId = userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public Long getShopId() {
        return shopId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public Long getUserId() {
        return userId;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void addStep(AgentStep step) {
        steps.add(step);
    }
}
