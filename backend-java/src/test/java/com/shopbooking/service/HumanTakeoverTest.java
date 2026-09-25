package com.shopbooking.service;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.agent.SystemPromptBuilder;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Shop;
import com.shopbooking.service.DeepSeekService.ChatResponse;
import com.shopbooking.service.DeepSeekService.ToolCall;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工接管机制：转人工后 AI 暂停应答、店员回复直达顾客会话、
 * 工单解决后 AI 恢复且能接上人工聊过的上下文、同一会话不重复建单。
 */
class HumanTakeoverTest extends IntegrationTestBase {

    @Autowired
    private EscalationService escalationService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private SystemPromptBuilder promptBuilder;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 转人工后AI暂停_店员回复直达_解决后AI恢复并接上上下文() {
        Shop shop = createCompletedShop();
        createItem(shop, "大厅 4 人桌", 5, 90);

        // 第 1 轮：AI 调 escalate_to_human 转人工
        when(deepSeek.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse(null,
                        List.of(new ToolCall("c1", "escalate_to_human",
                                Map.of("reason", "顾客投诉"))), 10))
                .thenReturn(new ChatResponse("已为您转接人工客服，店家会尽快联系您", null, 5));
        ResponseEntity<Map> r1 = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "我要投诉，上次的菜里有头发", "sessionKey", "takeover-1")),
                Map.class);
        assertEquals(200, r1.getStatusCode().value());
        assertEquals(Boolean.TRUE, r1.getBody().get("escalated"));

        // 第 2 轮：人工接管中——AI 不许被调用，只落库转给店家
        reset(deepSeek);
        ResponseEntity<Map> r2 = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "你们到底怎么解决？", "sessionKey", "takeover-1")),
                Map.class);
        assertEquals(200, r2.getStatusCode().value());
        assertEquals(Boolean.TRUE, r2.getBody().get("humanMode"), "人工接管期间必须标记 humanMode");
        verify(deepSeek, never()).chat(anyList(), anyList());
        Integer userMsgs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE role='user'", Integer.class);
        assertEquals(2, userMsgs, "人工模式下顾客消息仍要落库（店员工单可见）");

        // 店员回复：human 消息直达会话，工单自动进入处理中
        Map owner = registerViaApi("boss_takeover", "owner");
        String token = (String) owner.get("token");
        Long ticketId = jdbc.queryForObject("SELECT id FROM escalations LIMIT 1", Long.class);
        ResponseEntity<Map> r3 = rest.postForEntity("/api/escalations/" + ticketId + "/reply",
                authEntity(Map.of("content", "实在抱歉！本单免单并赠送优惠券，请留下您的称呼。"), token),
                Map.class);
        assertEquals(200, r3.getStatusCode().value());
        assertEquals("PROCESSING", r3.getBody().get("status"), "回复后工单自动进入处理中");
        String humanMsg = jdbc.queryForObject(
                "SELECT content FROM chat_messages WHERE role='human'", String.class);
        assertTrue(humanMsg != null && humanMsg.contains("免单"), "店员回复必须以 human 角色写入会话");

        // 顾客仍在人工模式（店员回复不清除接管状态）
        ResponseEntity<Map> r4 = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "我姓王", "sessionKey", "takeover-1")), Map.class);
        assertEquals(Boolean.TRUE, r4.getBody().get("humanMode"));

        // 店员解决工单 → AI 恢复，且历史中的人工回复要回喂给模型
        ResponseEntity<Map> r5 = rest.postForEntity("/api/escalations/" + ticketId + "/resolve",
                authEntity(Map.of("note", "已免单处理"), token), Map.class);
        assertEquals(200, r5.getStatusCode().value());

        when(deepSeek.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("感谢您的理解王女士/先生，期待您再次光临～", null, 5));
        ResponseEntity<Map> r6 = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "好的，那这事就这样吧", "sessionKey", "takeover-1")),
                Map.class);
        assertEquals(200, r6.getStatusCode().value());
        assertEquals(Boolean.FALSE, r6.getBody().get("humanMode"), "工单解决后 AI 必须恢复");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(deepSeek).chat(captor.capture(), anyList());
        boolean humanFed = captor.getValue().stream().anyMatch(m ->
                "assistant".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("店家人工回复"));
        assertTrue(humanFed, "人工回复必须以 assistant 身份带前缀回喂模型，AI 才能接上上下文");
    }

    @Test
    void 同一会话存在未解决工单时不重复建单() {
        Shop shop = createCompletedShop();
        Conversation c = conversationService.getOrCreate(shop.getId(), "takeover-dedup", null);
        escalationService.create(shop.getId(), c.getId(), "顾客投诉", "summary", null);
        escalationService.create(shop.getId(), c.getId(), "顾客投诉", "summary", null);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM escalations", Integer.class);
        assertEquals(1, count, "模型收尾轮再次调用 escalate 时不得产生重复工单");
    }

    @Test
    void 提示词包含三层分级应答策略() {
        Shop shop = createCompletedShop();
        String prompt = promptBuilder.build(shop, null);
        assertTrue(prompt.contains("三层分级应答"), "提示词必须写明三层分级策略");
        assertTrue(prompt.contains("第一层"), "第一层：店铺事实直接回答");
        assertTrue(prompt.contains("不要立刻转人工"), "查不到知识库时不得立刻转人工");
        assertTrue(prompt.contains("营业时间"), "店铺事实必须包含营业时间（第一层直接答的素材）");
    }
}
