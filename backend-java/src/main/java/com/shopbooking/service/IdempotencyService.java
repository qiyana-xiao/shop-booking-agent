package com.shopbooking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 幂等键：shop-booking:idem:{sessionId}:{turn}:{tool}:{sha256(args)}，TTL 10 分钟。
 * Redis 不可用时跳过（返回 null/empty），由数据库唯一索引与业务状态校验兜底，绝不静默吞掉重复写。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String PREFIX = "shop-booking:idem:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final SafeRedisService redis;
    private final ObjectMapper objectMapper;

    public IdempotencyService(SafeRedisService redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public String buildKey(String sessionId, int turn, String tool, Map<String, Object> args) {
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args == null ? Map.of() : new TreeMap<>(args));
        } catch (Exception e) {
            argsJson = "{}";
        }
        return PREFIX + sessionId + ":" + turn + ":" + tool + ":" + sha256(argsJson);
    }

    /** 命中幂等：直接返回首次结果 */
    public Optional<String> tryGet(String key) {
        String cached = redis.get(key);
        if (cached == null || "PENDING".equals(cached)) {
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    /**
     * 抢占执行权：TRUE=首次执行；FALSE=同参数请求正在处理（命中幂等，勿重复执行）；
     * NULL=Redis 不可用，跳过幂等（数据库兜底）。
     */
    public Boolean tryBegin(String key) {
        return redis.call(t -> Boolean.TRUE.equals(t.opsForValue().setIfAbsent(key, "PENDING", TTL)), null);
    }

    public void save(String key, String resultJson) {
        redis.set(key, resultJson, TTL);
    }

    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 重复写被数据库兜底拦截后的告警（不静默吞掉） */
    public void warnDuplicateBlocked(String detail) {
        log.warn("重复写被数据库唯一索引/状态校验拦截（幂等兜底生效）：{}", detail);
    }
}
