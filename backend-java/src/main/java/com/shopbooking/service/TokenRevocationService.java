package com.shopbooking.service;

import org.springframework.stereotype.Service;

import java.time.Duration;

/** JWT 注销黑名单：jti 写入 Redis，TTL 等于 Token 剩余有效期。Redis 不可用时注销降级（仅等待自然过期）。 */
@Service
public class TokenRevocationService {

    private static final String PREFIX = "shop-booking:jwt:revoked:";

    private final SafeRedisService redis;

    public TokenRevocationService(SafeRedisService redis) {
        this.redis = redis;
    }

    public void revoke(String jti, Duration remainingTtl) {
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            return;
        }
        redis.set(PREFIX + jti, "1", remainingTtl);
    }

    public boolean isRevoked(String jti) {
        return redis.hasKey(PREFIX + jti);
    }
}
