package com.panjia.console.common.util;

import java.security.SecureRandom;

/**
 * 授权码生成器
 * <p>
 * 格式：PJ-XXXX-XXXX-XXXX（大写字母和数字，排除易混淆字符）。
 * <p>
 * ★ S-2 修复：2 段（31^8 ≈ 2^39.6）在公网无限速场景下可被撞库
 * （1 万有效码时期望 ~8.5 亿次请求即命中）。扩到 3 段（31^12 ≈ 2^59.4）后
 * 撞库在算力上不可行。存量 2 段旧码不受影响（服务端按字符串精确匹配），
 * 仅新生成的授权码为新格式。
 */
public class AuthCodeGenerator {

    /** 排除易混淆字符：0、O、I、L、1 */
    private static final String CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int SEGMENT_LENGTH = 4;
    private static final int SEGMENTS = 3;
    private static final String PREFIX = "PJ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthCodeGenerator() {
    }

    /**
     * 生成授权码：PJ-XXXX-XXXX-XXXX（S-2：3 段，熵 2^59.4）
     *
     * @return 授权码字符串
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < SEGMENTS; i++) {
            sb.append('-');
            for (int j = 0; j < SEGMENT_LENGTH; j++) {
                sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            }
        }
        return sb.toString();
    }

    /**
     * 生成 licenseId：L + 12位随机字符
     *
     * @return licenseId
     */
    public static String generateLicenseId() {
        StringBuilder sb = new StringBuilder("L");
        for (int i = 0; i < 12; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 脱敏授权码：只显示后 4 位，其余用 * 代替
     *
     * @param authCode 原始授权码
     * @return 脱敏后的授权码
     */
    public static String mask(String authCode) {
        if (authCode == null || authCode.length() <= 4) {
            return "****";
        }
        int len = authCode.length();
        return "*".repeat(len - 4) + authCode.substring(len - 4);
    }
}
