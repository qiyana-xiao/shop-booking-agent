package com.shopbooking.dto;

/** 顾客端预约操作请求（游客以 sessionKey 标识，登录用户以 JWT 标识） */
public class BookingRequests {

    public record CancelRequest(String sessionKey, String reason) {
    }

    public record RescheduleRequest(String sessionKey, Long newSlotId) {
    }
}
