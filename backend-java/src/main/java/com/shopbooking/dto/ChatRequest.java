package com.shopbooking.dto;

/** 顾客对话请求：游客带 sessionKey，登录用户带 Authorization 头 */
public record ChatRequest(String sessionKey, String message) {
}
