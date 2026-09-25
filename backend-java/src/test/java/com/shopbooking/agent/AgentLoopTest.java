package com.shopbooking.agent;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Shop;
import com.shopbooking.service.ConversationService;
import com.shopbooking.service.DeepSeekService;
import com.shopbooking.service.DeepSeekService.ChatResponse;
import com.shopbooking.service.DeepSeekService.ToolCall;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用桩模型验证 Agent 主循环：要工具 → 回喂 → 再问 → 结束；超轮次/工具连续失败熔断转人工 */
class AgentLoopTest extends IntegrationTestBase {

    @Autowired
    private AgentLoop agentLoop;
    @Autowired
    private ConversationService conversationService;

    private Shop shop;
    private Conversation conversation;

    private void prepare() {
        shop = createCompletedShop();
        createItem(shop, "小包间 6 人", 3, 120);
        conversation = conversationService.getOrCreate(shop.getId(), "agent-loop-test", null);
    }

    @Test
    void 主循环_要工具回喂后正常结束() {
        prepare();
        when(deepSeek.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse(null,
                        List.of(new ToolCall("call-1", "query_service_items", Map.of())), 10))
                .thenReturn(new ChatResponse("我们有小包间 6 人，可坐 6 位", null, 5));

        AgentContext ctx = new AgentContext(conversation.getId(), shop.getId(), "agent-loop-test", null);
        AgentLoop.AgentRunResult result = agentLoop.run(ctx, conversation, "你们有什么包间");

        assertEquals("我们有小包间 6 人，可坐 6 位", result.reply());
        assertFalse(result.escalated());
        assertEquals(15, result.tokensUsed());
        assertEquals(1, ctx.getSteps().size());
        assertEquals("query_service_items", ctx.getSteps().get(0).getToolName());
        assertTrue(ctx.getSteps().get(0).getSuccess());

        // 工具结果必须以 role=tool + 正确的 tool_call_id 回喂给模型
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(deepSeek, org.mockito.Mockito.times(2)).chat(captor.capture(), anyList());
        List<Map<String, Object>> secondCall = captor.getAllValues().get(1);
        assertTrue(secondCall.stream().anyMatch(m ->
                "tool".equals(m.get("role")) && "call-1".equals(m.get("tool_call_id"))),
                "工具结果必须回喂给模型");
        // 顾客消息必须包裹 <customer_message> 标签（Prompt 注入防御）
        assertTrue(secondCall.stream().anyMatch(m ->
                "user".equals(m.get("role")) && String.valueOf(m.get("content")).contains("<customer_message>")));
    }

    @Test
    void 超轮次熔断转人工() {
        prepare();
        when(deepSeek.chat(anyList(), anyList()))
                .thenAnswer(inv -> new ChatResponse(null,
                        List.of(new ToolCall("c", "query_service_items", Map.of())), 100));

        AgentContext ctx = new AgentContext(conversation.getId(), shop.getId(), "agent-loop-test", null);
        AgentLoop.AgentRunResult result = agentLoop.run(ctx, conversation, "随便查查");

        assertTrue(result.escalated());
        assertTrue(result.reply().contains("转接人工"));
        assertEquals(8, ctx.getSteps().size());
        Integer escalations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escalations WHERE conversation_id = ?",
                Integer.class, conversation.getId());
        assertEquals(1, escalations);
    }

    @Test
    void 工具连续失败两次熔断转人工() {
        prepare();
        when(deepSeek.chat(anyList(), anyList()))
                .thenAnswer(inv -> new ChatResponse(null,
                        List.of(new ToolCall("c", "check_availability",
                                Map.of("date", "2020-01-01"))), 10));

        AgentContext ctx = new AgentContext(conversation.getId(), shop.getId(), "agent-loop-test", null);
        AgentLoop.AgentRunResult result = agentLoop.run(ctx, conversation, "查一下 2020 年的档期");

        assertTrue(result.escalated());
        assertTrue(result.reply().contains("转接人工"));
        // 第二次失败即熔断，不会跑满 8 轮
        assertEquals(2, ctx.getSteps().size());
        assertFalse(ctx.getSteps().get(0).getSuccess());
        Integer escalations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escalations WHERE reason LIKE '%TOOL_REPEATED_FAILURE%'",
                Integer.class);
        assertEquals(1, escalations);
    }
}
