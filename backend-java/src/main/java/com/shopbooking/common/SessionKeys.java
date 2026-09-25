package com.shopbooking.common;

import com.shopbooking.security.SecurityUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 游客会话标识：前端首次访问生成随机串存 localStorage，之后随 X-Session-Key 头提交。
 * 它同时是幂等键和"我的预约"的归属凭证，格式必须收紧，防止拼进 Redis key。
 *
 * 会话键的最终边界在 scopedForCurrentUser：
 * - 登录顾客一律使用账号专属键 user-{id}，不信任请求头——每个账号的聊天与预约天然隔离，也无法伪造他人会话；
 * - 未登录请求禁止使用 user- 前缀，防止游客冒充登录顾客；
 * - 店员/老板保留前端的预览会话键（本来就与顾客隔离）。
 */
public final class SessionKeys {

    public static final String HEADER = "X-Session-Key";

    private SessionKeys() {
    }

    public static String extract(HttpServletRequest request, String fallbackFromBody) {
        String key = sanitize(request.getHeader(HEADER));
        if (key == null) {
            key = sanitize(fallbackFromBody);
        }
        return scopedForCurrentUser(key);
    }

    public static String sanitize(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        return trimmed.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fa5:]+") ? trimmed : null;
    }

    private static String scopedForCurrentUser(String guestKey) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecurityUser user) {
            return "customer".equals(user.getRole()) ? "user-" + user.getId() : guestKey;
        }
        return guestKey != null && guestKey.startsWith("user-") ? null : guestKey;
    }
}
