package com.shopbooking.service;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * AI 降级路径：DeepSeek 超时/5xx/密钥未配置时绝不白屏——
 * FAQ 关键词兜底回答 + 自动创建转人工工单，顾客消息照常落库。
 */
class FaqFallbackTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeService knowledgeService;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void DeepSeek不可用_命中FAQ并自动转人工() {
        Shop shop = createCompletedShop();
        knowledgeService.create(shop.getId(), Map.of(
                "question", "门口可以停车吗？",
                "answer", "可以，店门口有免费停车位，高峰期可能需要等位。",
                "keywords", "停车 车位 泊车"));

        when(deepSeek.chat(anyList(), anyList()))
                .thenThrow(new DeepSeekService.DeepSeekUnavailableException("mock: api down"));

        ResponseEntity<Map> resp = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "请问门口可以停车吗", "sessionKey", "faq-fallback")),
                Map.class);

        assertEquals(200, resp.getStatusCode().value(), "降级不是故障，接口必须正常返回");
        Map body = resp.getBody();
        assertEquals(Boolean.TRUE, body.get("degraded"), "响应必须带 degraded 标记");
        String reply = String.valueOf(body.get("reply"));
        assertTrue(reply.contains("免费停车位"), "命中知识库时要把答案带给顾客：\n" + reply);
        assertTrue(reply.contains("转接人工"), "降级必须自动转人工兜底");

        Integer escalations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escalations WHERE reason LIKE '%降级%'", Integer.class);
        assertEquals(1, escalations, "必须自动创建转人工工单");
        String summary = jdbc.queryForObject(
                "SELECT summary FROM escalations LIMIT 1", String.class);
        assertTrue(summary != null && summary.contains("停车"), "工单摘要要带上顾客问题，店员不用重问");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void DeepSeek不可用_未命中FAQ时仍转人工且消息落库() {
        createCompletedShop();
        when(deepSeek.chat(anyList(), anyList()))
                .thenThrow(new DeepSeekService.DeepSeekUnavailableException("mock: timeout"));

        ResponseEntity<Map> resp = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "我想订明天晚上七点的包间", "sessionKey", "faq-miss")),
                Map.class);

        assertEquals(200, resp.getStatusCode().value());
        Map body = resp.getBody();
        assertEquals(Boolean.TRUE, body.get("degraded"));
        String reply = String.valueOf(body.get("reply"));
        assertTrue(reply.contains("转接人工"), "没有 FAQ 命中也要转人工：\n" + reply);
        assertFalse(reply.contains("可以预约"), "降级路径绝不能编造档期");

        Integer escalations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escalations", Integer.class);
        assertEquals(1, escalations);
        Integer messages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE role IN ('user','assistant')", Integer.class);
        assertEquals(2, messages, "顾客消息与降级回复都必须落库，历史回放可见");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 未完成开店配置时对话有礼貌拦截() {
        // 无店铺场景：ChatService 早退，不触发任何 AI 调用
        ResponseEntity<Map> resp = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "你好", "sessionKey", "no-setup")),
                Map.class);

        assertEquals(200, resp.getStatusCode().value());
        Map body = resp.getBody();
        assertEquals(Boolean.TRUE, body.get("degraded"));
        assertTrue(String.valueOf(body.get("reply")).contains("开店配置"));
    }
}
