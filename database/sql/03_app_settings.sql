-- AI 密钥等应用级设置存储表（密钥以 AES-GCM 加密后的密文保存，明文不出现在数据库与仓库中）
CREATE TABLE IF NOT EXISTS app_settings (
  setting_key   VARCHAR(64) NOT NULL COMMENT '设置项，如 deepseek_api_key',
  setting_value TEXT        NOT NULL COMMENT '加密后的设置值',
  updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='应用级设置（含界面配置的 AI 密钥密文）';
