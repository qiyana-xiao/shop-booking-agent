package com.shopbooking.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具执行结果：成功时 data 回喂给模型，失败时错误信息也回喂（模型据此改口） */
public class ToolResult {

    private final boolean success;
    private final Map<String, Object> data;

    private ToolResult(boolean success, Map<String, Object> data) {
        this.success = success;
        this.data = data;
    }

    public static ToolResult ok(Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.putAll(data);
        return new ToolResult(true, payload);
    }

    public static ToolResult fail(String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", error);
        return new ToolResult(false, payload);
    }

    /** 从幂等缓存还原结果（不重新包装，保持首次执行的载荷） */
    public static ToolResult restore(Map<String, Object> data) {
        return new ToolResult(Boolean.TRUE.equals(data.get("success")), data);
    }

    public boolean isSuccess() {
        return success;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
