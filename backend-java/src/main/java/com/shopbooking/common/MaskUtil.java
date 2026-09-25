package com.shopbooking.common;

public final class MaskUtil {

    private MaskUtil() {
    }

    /** 手机号脱敏：138****5678 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(Math.min(name.length() - 1, 2));
    }
}
