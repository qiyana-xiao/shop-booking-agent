-- 内置老板账号（账号密码见 README「快速开始」；重复执行会把该账号密码重置为文档值）
-- boss 为 owner 角色，首次登录后走开店向导即可正常营业
-- 密码哈希由 BCrypt 生成，与后端 PasswordEncoder 校验兼容
INSERT INTO users (username, password_hash, role, shop_id, phone)
VALUES ('boss', '$2b$10$LKWfTua2oagZroljYqfQ4OUuM65jD93J/DT5Mwx/R3Fw449oc12C.', 'owner', NULL, NULL)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = 'owner';
