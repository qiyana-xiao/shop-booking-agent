package com.shopbooking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Redis 限流：对话 20 次/分/会话。Redis 不可用时放行并告警（fail-open）。 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final SafeRedisService redis;

    public RateLimitService(SafeRedisService redis) {
        this.redis = redis;
    }

    public boolean allowChat(String sessionKey, String ip, int limitPerMinute) {
        String key = "shop-booking:rate:chat:" + (sessionKey == null ? ip : sessionKey);
        Long count = redis.increment(key, Duration.ofMinutes(1));
        if (count == null) {
            log.warn("限流计数不可用（Redis 降级），本次放行");
            return true; // fail-open
        }
        return count <= limitPerMinute;
    }
}
