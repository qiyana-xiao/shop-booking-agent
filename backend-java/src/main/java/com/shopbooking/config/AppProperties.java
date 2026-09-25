package com.shopbooking.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final DeepSeek deepseek = new DeepSeek();
    private final Chat chat = new Chat();
    private final Booking booking = new Booking();
    private int retentionDays = 90;
    private String corsAllowedOrigins = "http://localhost:5173";

    @PostConstruct
    void validate() {
        if (jwt.getSecret() == null || jwt.getSecret().getBytes().length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置或长度不足 32 字节：请复制 backend-java/.env.example 为 .env 并填写随机密钥后重试");
        }
    }

    public Jwt getJwt() {
        return jwt;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public Chat getChat() {
        return chat;
    }

    public Booking getBooking() {
        return booking;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(String corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public static class Jwt {
        private String secret;
        private int expireDays = 7;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public int getExpireDays() {
            return expireDays;
        }

        public void setExpireDays(int expireDays) {
            this.expireDays = expireDays;
        }
    }

    public static class DeepSeek {
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 60000;
        private boolean strictTools = true;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public boolean isStrictTools() {
            return strictTools;
        }

        public void setStrictTools(boolean strictTools) {
            this.strictTools = strictTools;
        }
    }

    public static class Chat {
        private int rateLimitPerMinute = 20;
        private int maxMessageLength = 500;
        private int maxHistoryMessages = 12;
        private int summaryTriggerTurns = 16;
        private int maxTurns = 8;
        private int tokenBudget = 30000;

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public int getMaxMessageLength() {
            return maxMessageLength;
        }

        public void setMaxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
        }

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages;
        }

        public int getSummaryTriggerTurns() {
            return summaryTriggerTurns;
        }

        public void setSummaryTriggerTurns(int summaryTriggerTurns) {
            this.summaryTriggerTurns = summaryTriggerTurns;
        }

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public int getTokenBudget() {
            return tokenBudget;
        }

        public void setTokenBudget(int tokenBudget) {
            this.tokenBudget = tokenBudget;
        }
    }

    public static class Booking {
        private int holdMinutes = 15;
        private int slotDays = 30;

        public int getHoldMinutes() {
            return holdMinutes;
        }

        public void setHoldMinutes(int holdMinutes) {
            this.holdMinutes = holdMinutes;
        }

        public int getSlotDays() {
            return slotDays;
        }

        public void setSlotDays(int slotDays) {
            this.slotDays = slotDays;
        }
    }
}
