package com.shopbooking.security;

import com.shopbooking.config.AppProperties;
import com.shopbooking.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final AppProperties props;
    private final SecretKey key;
    private final TokenRevocationService revocation;

    public JwtService(AppProperties props, TokenRevocationService revocation) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.revocation = revocation;
    }

    public String generate(Long userId, String username, String role) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofDays(props.getJwt().getExpireDays()));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** 解析并校验（签名/过期/注销黑名单），失败返回 null */
    public Claims parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (revocation.isRevoked(claims.getId())) {
                return null;
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    public Duration remainingTtl(Claims claims) {
        Date exp = claims.getExpiration();
        return Duration.between(Instant.now(), exp.toInstant());
    }
}
