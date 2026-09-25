package com.shopbooking.service;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 密钥统一入口：界面配置（数据库密文）优先，其次 .env / 环境变量兜底。
 * 老板在「店铺设置 → AI 客服设置」中保存的密钥经 AES-GCM 加密后存 app_settings 表。
 */
@Service
public class AiKeyService {

    private static final String SETTING_KEY = "deepseek_api_key";

    private final JdbcTemplate jdbcTemplate;
    private final String envKey;
    private final String jwtSecret;

    public AiKeyService(JdbcTemplate jdbcTemplate,
                        com.shopbooking.config.AppProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.envKey = props.getDeepseek().getApiKey();
        this.jwtSecret = props.getJwt().getSecret();
    }

    /** 实际生效的密钥：数据库（界面配置）优先，其次环境变量 */
    public String effectiveKey() {
        String fromDb = loadFromDb();
        if (fromDb != null && !fromDb.isBlank()) {
            return fromDb;
        }
        return (envKey == null || envKey.isBlank()) ? null : envKey;
    }

    public boolean configured() {
        return effectiveKey() != null;
    }

    /** 当前生效来源：database（界面配置）/ env（.env 配置）/ none */
    public String source() {
        String fromDb = loadFromDb();
        if (fromDb != null && !fromDb.isBlank()) {
            return "database";
        }
        if (envKey != null && !envKey.isBlank()) {
            return "env";
        }
        return "none";
    }

    /** 掩码展示：sk-1****abcd，明文永不出现在接口返回中 */
    public String maskedKey() {
        String key = effectiveKey();
        if (key == null) {
            return null;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    public void saveKey(String plainKey) {
        String encrypted = SettingCrypto.encrypt(plainKey.trim(), jwtSecret);
        jdbcTemplate.update(
                "INSERT INTO app_settings (setting_key, setting_value, updated_at) "
                        + "VALUES (?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()",
                SETTING_KEY, encrypted);
    }

    /** 清除界面配置的密钥，回退到 .env 配置（如有） */
    public void clearKey() {
        jdbcTemplate.update("DELETE FROM app_settings WHERE setting_key = ?", SETTING_KEY);
    }

    private String loadFromDb() {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT setting_value FROM app_settings WHERE setting_key = ?",
                    String.class, SETTING_KEY);
            if (rows.isEmpty()) {
                return null;
            }
            return SettingCrypto.decrypt(rows.get(0), jwtSecret);
        } catch (DataAccessException exception) {
            // app_settings 表尚未创建（未执行 03_app_settings.sql）时按未配置处理
            return null;
        }
    }
}
