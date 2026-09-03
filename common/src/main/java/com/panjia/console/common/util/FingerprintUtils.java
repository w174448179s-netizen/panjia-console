package com.panjia.console.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 指纹工具类
 * <p>
 * 使用 SHA-256 对指纹进行哈希存储，不存原文。
 */
public class FingerprintUtils {

    private FingerprintUtils() {
    }

    /**
     * 计算指纹 SHA-256 哈希（十六进制小写）
     *
     * @param fingerprint 原始指纹字符串
     * @return SHA-256 十六进制哈希值
     */
    public static String sha256(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            throw new IllegalArgumentException("fingerprint cannot be empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是标准算法，不可能不存在
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 计算 authCode + fingerprint + version 的组合哈希
     * <p>
     * 用于 JWT 校验的第二步：重算哈希比对 claims.fpHash
     *
     * @param authCode  授权码
     * @param fingerprint 指纹原文
     * @param version   产品版本
     * @return SHA-256 十六进制哈希值
     */
    public static String computeFpHash(String authCode, String fingerprint, String version) {
        String combined = authCode + "|" + fingerprint + "|" + (version != null ? version : "");
        return sha256(combined);
    }
}
