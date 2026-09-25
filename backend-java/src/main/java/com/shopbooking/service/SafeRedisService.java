package com.shopbooking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Function;

/**
 * Redis 安全访问层：Redis 不可用时所有操作静默降级（返回回退值），
 * 不抛异常、不阻塞业务。Redis 只存可重建状态，清空或宕机不丢订单。
 */
@Service
public class SafeRedisService {

    private static final Logger log = LoggerFactory.getLogger(SafeRedisService.class);

    private final StringRedisTemplate template;
    private volatile boolean available = true;
    private volatile long lastCheckAt = 0;
    private static final long RECHECK_INTERVAL_MS = 30_000;

    public SafeRedisService(StringRedisTemplate template) {
        this.template = template;
    }

    private boolean checkAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < RECHECK_INTERVAL_MS) {
            return available;
        }
        synchronized (this) {
            if (now - lastCheckAt < RECHECK_INTERVAL_MS) {
                return available;
            }
            try {
                RedisConnectionFactory factory = template.getConnectionFactory();
                if (factory != null) {
                    factory.getConnection().ping();
                }
                if (!available) {
                    log.info("Redis 已恢复连接");
                }
                available = true;
            } catch (Exception e) {
                if (available) {
                    log.warn("Redis 不可用，相关能力自动降级（对话记忆/幂等/限流走数据库与内存兜底）：{}", e.getMessage());
                }
                available = false;
            }
            lastCheckAt = now;
        }
        return available;
    }

    public <T> T call(Function<StringRedisTemplate, T> fn, T fallback) {
        if (!checkAvailable()) {
            return fallback;
        }
        try {
            return fn.apply(template);
        } catch (Exception e) {
            available = false;
            lastCheckAt = System.currentTimeMillis();
            log.warn("Redis 操作失败，降级处理：{}", e.getMessage());
            return fallback;
        }
    }

    public String get(String key) {
        return call(t -> t.opsForValue().get(key), null);
    }

    public void set(String key, String value, Duration ttl) {
        call(t -> {
            t.opsForValue().set(key, value, ttl);
            return true;
        }, false);
    }

    /** 不存在才写入，返回是否抢到 */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return call(t -> Boolean.TRUE.equals(t.opsForValue().setIfAbsent(key, value, ttl)), false);
    }

    public void delete(String key) {
        call(t -> {
            t.delete(key);
            return true;
        }, false);
    }

    public boolean hasKey(String key) {
        return call(t -> Boolean.TRUE.equals(t.hasKey(key)), false);
    }

    public Long increment(String key, Duration ttl) {
        return call(t -> {
            Long count = t.opsForValue().increment(key);
            if (count != null && count == 1) {
                t.expire(key, ttl);
            }
            return count;
        }, null);
    }

    public boolean isAvailable() {
        return checkAvailable();
    }
}
