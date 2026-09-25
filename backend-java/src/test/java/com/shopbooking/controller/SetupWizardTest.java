package com.shopbooking.controller;

import com.shopbooking.IntegrationTestBase;
import com.shopbooking.service.DeepSeekService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 4 步开店向导：一次提交开店全部配置（店铺信息+营业时间+服务项+档期）。
 * 幂等可重放：中断可恢复、重复提交不清数据，老板填错随时重来。
 */
class SetupWizardTest extends IntegrationTestBase {

    private String registerOwner() {
        Map body = registerViaApi("boss", "owner");
        assertNotNull(body);
        return (String) body.get("token");
    }

    private Map<String, Object> wizardBody(String name, boolean useTemplates, String industry) {
        return Map.of(
                "name", name,
                "address", "幸福路 8 号",
                "phone", "13900000000",
                "slotGranularityMinutes", 60,
                "hours", Map.of("MONDAY", Map.of("closed", true)),
                "useTemplates", useTemplates,
                "industry", industry == null ? "" : industry);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 状态接口_初始无店铺无老板() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/setup/status", Map.class);
        assertEquals(200, resp.getStatusCode().value());
        Map body = resp.getBody();
        assertEquals(Boolean.FALSE, body.get("shopExists"));
        assertEquals(Boolean.FALSE, body.get("setupCompleted"));
        assertEquals(Boolean.FALSE, body.get("hasOwner"));
        // deepSeek 未配置也必须如实上报，部署检查一眼看清
        assertEquals(Boolean.FALSE, body.get("deepSeekConfigured"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 完整向导_行业模板一键开店并生成档期() {
        String token = registerOwner();

        ResponseEntity<Map> resp = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("老王饭店", true, "restaurant"), token), Map.class);

        assertEquals(200, resp.getStatusCode().value());
        Map body = resp.getBody();
        assertEquals(Boolean.TRUE, body.get("firstTime"));
        assertEquals(4, ((Number) body.get("serviceItemsCreated")).intValue(),
                "restaurant 模板应有 4 个服务项");
        assertTrue(((Number) body.get("slotsGenerated")).intValue() > 0, "开店即出 30 天档期");
        assertNotNull(body.get("shopId"));

        // 状态翻转为已开业
        Map status = rest.getForEntity("/api/setup/status", Map.class).getBody();
        assertEquals(Boolean.TRUE, status.get("setupCompleted"));
        assertEquals("老王饭店", status.get("shopName"));
        assertEquals(Boolean.TRUE, status.get("hasOwner"));

        // 顾客此时可以对话（打桩模型直答，验证开店拦截已解除）
        when(deepSeek.chat(anyList(), anyList()))
                .thenReturn(new DeepSeekService.ChatResponse("您好，欢迎光临老王饭店！", null, 5));
        ResponseEntity<Map> chat = rest.postForEntity("/api/chat",
                jsonEntity(Map.of("message", "你好", "sessionKey", "after-setup")), Map.class);
        assertEquals(200, chat.getStatusCode().value());
        assertEquals("您好，欢迎光临老王饭店！", chat.getBody().get("reply"));
        assertEquals(Boolean.FALSE, chat.getBody().get("degraded"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 向导中断可恢复_未配服务项不标记完成() {
        String token = registerOwner();

        // 第一步：只填店铺信息，没有服务项 → 报错但不丢已保存信息
        ResponseEntity<Map> half = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("半成品小店", false, null), token), Map.class);
        assertEquals(400, half.getStatusCode().value());

        Map status = rest.getForEntity("/api/setup/status", Map.class).getBody();
        assertEquals(Boolean.TRUE, status.get("shopExists"), "店铺信息应已保存");
        assertEquals(Boolean.FALSE, status.get("setupCompleted"), "服务项没配好不能标记完成");

        // 第二步：重新提交补上服务项 → 开店成功
        ResponseEntity<Map> done = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("半成品小店", true, "beauty"), token), Map.class);
        assertEquals(200, done.getStatusCode().value());
        assertTrue(((Number) done.getBody().get("serviceItemsCreated")).intValue() > 0);

        Map statusAfter = rest.getForEntity("/api/setup/status", Map.class).getBody();
        assertEquals(Boolean.TRUE, statusAfter.get("setupCompleted"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 向导重复提交幂等_服务项与档期不重复() {
        String token = registerOwner();

        ResponseEntity<Map> first = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("幂等餐馆", true, "restaurant"), token), Map.class);
        assertEquals(200, first.getStatusCode().value());
        Integer slotsAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM time_slots", Integer.class);

        // 老板手抖重复提交（或想改营业时间重跑向导）
        ResponseEntity<Map> second = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("幂等餐馆", true, "restaurant"), token), Map.class);

        assertEquals(200, second.getStatusCode().value());
        assertEquals(0, ((Number) second.getBody().get("serviceItemsCreated")).intValue(),
                "同名服务项必须跳过，不产生重复");
        Integer slotsAfterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM time_slots", Integer.class);
        assertEquals(slotsAfterFirst, slotsAfterSecond, "已存在的档期不重复生成");

        Integer items = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_items WHERE name = '大厅 2 人桌'", Integer.class);
        assertEquals(1, items);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 非法行业模板与自定义服务项() {
        String token = registerOwner();

        ResponseEntity<Map> badIndustry = rest.postForEntity("/api/setup/wizard",
                authEntity(wizardBody("乱填店", true, "hospital"), token), Map.class);
        assertEquals(400, badIndustry.getStatusCode().value());

        // 自定义服务项路线：不套模板，手工填
        ResponseEntity<Map> resp = rest.postForEntity("/api/setup/wizard",
                authEntity(Map.of(
                        "name", "手工作坊",
                        "hours", Map.of("MONDAY", Map.of("closed", true)),
                        "useTemplates", false,
                        "serviceItems", List.of(Map.of(
                                "name", "剪发", "capacityPerUnit", 1, "unitCount", 2,
                                "durationMinutes", 45, "price", "38"))), token), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, ((Number) resp.getBody().get("serviceItemsCreated")).intValue());
        assertTrue(((Number) resp.getBody().get("slotsGenerated")).intValue() > 0);
    }
}
