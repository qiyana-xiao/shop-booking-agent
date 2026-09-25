package com.shopbooking.common;

public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(message, 400);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(message, 401);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(message, 403);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(message, 404);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(message, 409);
    }

    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(message, 429);
    }
}
