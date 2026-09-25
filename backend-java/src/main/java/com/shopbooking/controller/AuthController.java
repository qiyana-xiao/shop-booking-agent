package com.shopbooking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.common.SessionKeys;
import com.shopbooking.dto.AuthRequests;
import com.shopbooking.entity.Booking;
import com.shopbooking.entity.Conversation;
import com.shopbooking.entity.Shop;
import com.shopbooking.entity.User;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.mapper.ConversationMapper;
import com.shopbooking.mapper.UserMapper;
import com.shopbooking.security.JwtService;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.ShopService;
import com.shopbooking.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String USERNAME_PATTERN = "^[\\u4e00-\\u9fa5A-Za-z0-9_]{2,20}$";

    private final UserMapper userMapper;
    private final BookingMapper bookingMapper;
    private final ConversationMapper conversationMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final TokenRevocationService revocation;
    private final ShopService shopService;

    public AuthController(UserMapper userMapper, BookingMapper bookingMapper,
                          ConversationMapper conversationMapper, PasswordEncoder passwordEncoder,
                          AuthenticationManager authManager, JwtService jwtService,
                          TokenRevocationService revocation, ShopService shopService) {
        this.userMapper = userMapper;
        this.bookingMapper = bookingMapper;
        this.conversationMapper = conversationMapper;
        this.passwordEncoder = passwordEncoder;
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.revocation = revocation;
        this.shopService = shopService;
    }

    /**
     * 注册：角色仅允许 customer/staff。
     * 例外：系统还没有任何 owner 时允许注册 owner（首次部署引导），之后 owner 只能由数据库授权升级，
     * 不存在默认老板账号。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody AuthRequests.RegisterRequest req,
                                        HttpServletRequest request) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!username.matches(USERNAME_PATTERN)) {
            throw BusinessException.badRequest("用户名需为 2-20 位中文、字母、数字或下划线");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw BusinessException.badRequest("密码至少 6 位");
        }
        String role = req.role() == null || req.role().isBlank() ? "customer" : req.role();
        boolean ownerBootstrap = false;
        if ("owner".equals(role)) {
            Long owners = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "owner"));
            if (owners > 0) {
                throw BusinessException.badRequest("老板账号已存在，owner 角色需由数据库授权升级");
            }
            ownerBootstrap = true;
        } else if (!List.of("customer", "staff").contains(role)) {
            throw BusinessException.badRequest("角色只能是 customer 或 staff");
        }

        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("用户名已被占用");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRole(role);
        Shop shop = shopService.primaryShop();
        user.setShopId(shop == null ? null : shop.getId());
        userMapper.insert(user);

        String token = jwtService.generate(user.getId(), user.getUsername(), user.getRole());
        claimGuestData(request, user.getId(), user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", view(user));
        result.put("ownerBootstrap", ownerBootstrap);
        return result;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody AuthRequests.LoginRequest req,
                                     HttpServletRequest request) {
        if (req.username() == null || req.password() == null) {
            throw BusinessException.badRequest("用户名和密码不能为空");
        }
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username().trim(), req.password()));
        } catch (BadCredentialsException e) {
            throw BusinessException.badRequest("用户名或密码不正确");
        }
        SecurityUser user = (SecurityUser) auth.getPrincipal();
        String token = jwtService.generate(user.getId(), user.getUsername(), user.getRole());
        claimGuestData(request, user.getId(), user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", view(user.getId(), user.getUsername(), user.getRole()));
        return result;
    }

    /**
     * 顾客登录/注册后认领游客期数据：把当前浏览器游客键下的预约与会话
     * 改挂到账号专属键（user-{id}）名下，聊天记录与预约无缝延续到账号里；
     * 之后同一浏览器再登录其他账号，也看不到这些数据。
     */
    private void claimGuestData(HttpServletRequest request, Long userId, String role) {
        if (!"customer".equals(role)) {
            return;
        }
        String guestKey = SessionKeys.sanitize(request.getHeader(SessionKeys.HEADER));
        if (guestKey == null || guestKey.startsWith("user-")) {
            return;
        }
        String userKey = "user-" + userId;
        bookingMapper.update(null, new LambdaUpdateWrapper<Booking>()
                .eq(Booking::getCustomerSessionId, guestKey)
                .set(Booking::getCustomerUserId, userId)
                .set(Booking::getCustomerSessionId, userKey));
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getSessionKey, guestKey)
                .set(Conversation::getUserId, userId)
                .set(Conversation::getSessionKey, userKey));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        SecurityUser user = currentUser();
        if (user == null) {
            throw BusinessException.badRequest("未登录");
        }
        return view(user.getId(), user.getUsername(), user.getRole());
    }

    /** 注销：jti 写入 Redis 黑名单，TTL 等于 Token 剩余有效期 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String header) {
        if (header != null && header.startsWith("Bearer ")) {
            Claims claims = jwtService.parse(header.substring(7));
            if (claims != null) {
                revocation.revoke(claims.getId(), jwtService.remainingTtl(claims));
            }
        }
        return Map.of("ok", true);
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }

    private Map<String, Object> view(User user) {
        return view(user.getId(), user.getUsername(), user.getRole());
    }

    private Map<String, Object> view(Long id, String username, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        m.put("role", role);
        return m;
    }
}
