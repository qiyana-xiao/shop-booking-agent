package com.shopbooking.dto;

/** 认证请求体。角色：customer/staff；owner 仅在系统还没有老板时允许（首个部署引导）。 */
public class AuthRequests {

    public record RegisterRequest(String username, String password, String role) {
    }

    public record LoginRequest(String username, String password) {
    }
}
