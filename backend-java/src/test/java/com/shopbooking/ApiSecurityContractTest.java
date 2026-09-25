package com.shopbooking;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全契约：谁在什么角色下能访问什么。
 * 顾客链路全部匿名可达；老板/店员后台必须持有效 JWT；角色越权直接 403。
 */
class ApiSecurityContractTest extends IntegrationTestBase {

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    /** 返回 JSON 数组的接口（today/calendar 等）只断言状态码，按 String 接收 */
    private int getStatus(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode().value();
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String register(String username, String role) {
        Map body = registerViaApi(username, role);
        assertNotNull(body, "注册应成功：" + username);
        return (String) body.get("token");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 公开接口_健康检查与顾客链路无需登录() {
        assertEquals(200, rest.getForEntity("/api/health", Map.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/api/health/ready", Map.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/api/setup/status", Map.class).getStatusCode().value());

        // 匿名对话（无店铺时也应是业务性回复而非 401）
        ResponseEntity<Map> chat = post("/api/chat",
                Map.of("message", "你好", "sessionKey", "sec-anon"), null);
        assertEquals(200, chat.getStatusCode().value());
        assertEquals(200, rest.getForEntity("/api/chat/history?sessionKey=sec-anon", Map.class)
                .getStatusCode().value());
        // /api/bookings/my 返回 JSON 数组，只断言状态码
        assertEquals(200, rest.getForEntity("/api/bookings/my?sessionKey=sec-anon", String.class)
                .getStatusCode().value());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 后台接口_未登录一律401() {
        for (String path : new String[]{
                "/api/dashboard", "/api/slots/calendar?year=2026&month=9",
                "/api/bookings/today", "/api/knowledge/templates",
                "/api/export/bookings.csv", "/api/service-items/templates", "/api/shop"}) {
            assertEquals(401, get(path, null).getStatusCode().value(), "未登录访问 " + path);
        }
        assertEquals(401, post("/api/setup/wizard",
                Map.of("name", "黑店", "useTemplates", true, "industry", "restaurant"), null)
                .getStatusCode().value());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 伪造或损坏的JWT按未登录处理() {
        assertEquals(401, get("/api/dashboard", "fake.token.value").getStatusCode().value());
        // 用一个真实格式但密钥错误的 token
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnopqrstuvwxyz012345";
        assertEquals(401, get("/api/dashboard", forged).getStatusCode().value());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 顾客角色访问老板后台一律403() {
        createCompletedShop();
        String customer = register("cust_sec", "customer");

        assertEquals(403, get("/api/dashboard", customer).getStatusCode().value());
        assertEquals(403, get("/api/knowledge/templates", customer).getStatusCode().value());
        assertEquals(403, post("/api/setup/wizard",
                Map.of("name", "黑店", "useTemplates", true, "industry", "restaurant"), customer)
                .getStatusCode().value());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 店员可用档期看板但动不了开店配置() {
        createCompletedShop();
        String staff = register("staff_sec", "staff");

        assertEquals(200, get("/api/dashboard", staff).getStatusCode().value(), "店员应能看数据看板");
        assertEquals(200, getStatus("/api/bookings/today", staff), "店员应能看今日预约");
        assertEquals(403, post("/api/setup/wizard",
                Map.of("name", "黑店", "useTemplates", true, "industry", "restaurant"), staff)
                .getStatusCode().value(), "开店配置是老板专属");
        assertEquals(403, get("/api/knowledge/templates", staff).getStatusCode().value());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 老板可访问全部后台且重复注册owner被拒() {
        createCompletedShop();
        String owner = register("owner_sec", "owner");

        assertEquals(200, get("/api/dashboard", owner).getStatusCode().value());
        assertEquals(200, getStatus("/api/slots/calendar?year=2026&month=9", owner));
        assertEquals(200, get("/api/knowledge/templates", owner).getStatusCode().value());

        // owner 只能有一个：第二次注册必须被拒，防止任意人提权
        ResponseEntity<Map> second = rest.postForEntity("/api/auth/register",
                jsonEntity(Map.of("username", "owner_two", "password", "pass123456", "role", "owner")),
                Map.class);
        assertTrue(second.getStatusCode().is4xxClientError(), "owner 重复注册必须失败");
    }
}
