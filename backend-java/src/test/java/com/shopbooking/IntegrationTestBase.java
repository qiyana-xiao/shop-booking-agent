package com.shopbooking;

import com.shopbooking.entity.ServiceItem;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.TimeSlot;
import com.shopbooking.mapper.ServiceItemMapper;
import com.shopbooking.mapper.ShopMapper;
import com.shopbooking.mapper.TimeSlotMapper;
import com.shopbooking.service.DeepSeekService;
import com.shopbooking.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.mockito.Mockito.when;

/**
 * 集成测试基类：H2 内存库（MySQL 模式）跑真实 SQL，
 * Redis 模拟为不可用（验证"清空 Redis 不丢订单"的降级设计），
 * DeepSeek 一律打桩（测试永不外呼）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ShopService shopService;
    @Autowired
    protected ShopMapper shopMapper;
    @Autowired
    protected ServiceItemMapper itemMapper;
    @Autowired
    protected TimeSlotMapper slotMapper;

    @MockBean
    protected StringRedisTemplate redisTemplate;
    @MockBean
    protected DeepSeekService deepSeek;

    @BeforeEach
    void redisDown() {
        when(redisTemplate.getConnectionFactory())
                .thenThrow(new IllegalStateException("redis down for test"));
        // 换掉默认的 HttpURLConnection 工厂：它对带 body 的 POST 收到 401 时
        // 必抛 HttpRetryException("cannot retry ... in streaming mode")，
        // java.net.http 的 JdkClientHttpRequestFactory 无此行为，可正常断言 401
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @AfterEach
    void cleanTables() {
        for (String table : new String[]{
                "bookings", "time_slots", "service_items", "agent_steps", "chat_messages",
                "conversations", "knowledge_items", "escalations", "shops", "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /** 已完成开店配置的店铺（周一休息，其余 09:00-21:00） */
    protected Shop createCompletedShop() {
        Shop shop = new Shop();
        shop.setName("测试小馆");
        shop.setAddress("测试路 1 号");
        shop.setPhone("13800000000");
        shop.setSlotGranularityMinutes(60);
        shop.setTimezone("Asia/Shanghai");
        shop.setSetupCompleted(true);
        shop.setNotificationEnabled(true);
        shop.setOpenHours("""
                {"MONDAY":{"open":"09:00","close":"21:00","closed":true},
                "TUESDAY":{"open":"09:00","close":"21:00","closed":false},
                "WEDNESDAY":{"open":"09:00","close":"21:00","closed":false},
                "THURSDAY":{"open":"09:00","close":"21:00","closed":false},
                "FRIDAY":{"open":"09:00","close":"21:00","closed":false},
                "SATURDAY":{"open":"09:00","close":"21:00","closed":false},
                "SUNDAY":{"open":"09:00","close":"21:00","closed":false}}
                """);
        shopService.save(shop);
        return shopMapper.selectById(shop.getId());
    }

    protected ServiceItem createItem(Shop shop, String name, int unitCount, int durationMinutes) {
        ServiceItem item = new ServiceItem();
        item.setShopId(shop.getId());
        item.setName(name);
        item.setCapacityPerUnit(10);
        item.setUnitCount(unitCount);
        item.setDurationMinutes(durationMinutes);
        item.setAdvanceDays(30);
        item.setSortOrder(0);
        item.setStatus("ACTIVE");
        itemMapper.insert(item);
        return itemMapper.selectById(item.getId());
    }

    protected TimeSlot createSlot(Shop shop, ServiceItem item, LocalDate date,
                                  LocalTime start, int durationMinutes, int capacity) {
        TimeSlot slot = new TimeSlot();
        slot.setShopId(shop.getId());
        slot.setServiceItemId(item.getId());
        slot.setSlotDate(date);
        slot.setStartTime(start);
        slot.setEndTime(start.plusMinutes(durationMinutes));
        slot.setCapacity(capacity);
        slot.setBookedCount(0);
        slot.setVersion(0);
        slot.setStatus("OPEN");
        slotMapper.insert(slot);
        return slotMapper.selectById(slot.getId());
    }

    /** 未来的某天（跳过周一休息日） */
    protected LocalDate futureOpenDate(int plusDays) {
        LocalDate d = LocalDate.now().plusDays(plusDays);
        while (d.getDayOfWeek().getValue() == 1) {
            d = d.plusDays(1);
        }
        return d;
    }

    protected Map<String, Object> registerViaApi(String username, String role) {
        return rest.postForEntity("/api/auth/register",
                jsonEntity(Map.of("username", username, "password", "pass123456", "role", role)),
                Map.class).getBody();
    }

    protected org.springframework.http.HttpEntity<Map<String, Object>> jsonEntity(
            Map<String, Object> body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }

    protected org.springframework.http.HttpEntity<Map<String, Object>> authEntity(
            Map<String, Object> body, String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return new org.springframework.http.HttpEntity<>(body, headers);
    }
}
