package com.shopbooking.controller;

import com.shopbooking.service.DeepSeekService;
import com.shopbooking.service.SafeRedisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource dataSource;
    private final SafeRedisService redis;
    private final DeepSeekService deepSeek;

    public HealthController(DataSource dataSource, SafeRedisService redis, DeepSeekService deepSeek) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.deepSeek = deepSeek;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", java.time.LocalDateTime.now().toString());
        return m;
    }

    /** 就绪检查：MySQL 必须可用；Redis 降级不阻塞（业务可重建）；DeepSeek 未配置仅提示 */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean mysqlOk;
        try (Connection conn = dataSource.getConnection()) {
            mysqlOk = conn.isValid(2);
        } catch (Exception e) {
            mysqlOk = false;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mysql", mysqlOk ? "UP" : "DOWN");
        m.put("redis", redis.isAvailable() ? "UP" : "DEGRADED");
        m.put("deepseek", deepSeek.configured() ? "UP" : "NOT_CONFIGURED");
        m.put("status", mysqlOk ? "READY" : "NOT_READY");
        return mysqlOk ? ResponseEntity.ok(m) : ResponseEntity.status(503).body(m);
    }
}
