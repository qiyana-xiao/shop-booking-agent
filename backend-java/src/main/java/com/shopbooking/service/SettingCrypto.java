package com.shopbooking.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 加解密工具：用于把界面配置的 AI 密钥以密文落库。
 * 加密密钥由 JWT_SECRET 派生（SHA-256），仓库与数据库中都不出现密钥明文。
 */
public final class SettingCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private SettingCrypto() {
    }

    public static String encrypt(String plain, String secret) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception exception) {
            throw new IllegalStateException("设置值加密失败", exception);
        }
    }

    /** 解密失败（如 JWT_SECRET 已更换）返回 null，调用方按未配置处理 */
    public static String decrypt(String encoded, String secret) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(secret), new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }

    private static SecretKeySpec deriveKey(String secret) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
