package com.panjia.console.common.util;

/**
 * 日志注入消毒工具（S-6 修复）。
 * <p>
 * 攻击面：requestId / instanceId / company 等客户端可控字段未消毒直接进入
 * [req={}] 日志前缀与业务日志，CRLF 可伪造日志行、干扰审计与告警。
 * <p>
 * 用法：在 DTO 反序列化入口（setter）消毒，业务层拿到的即安全值。
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /**
     * 去除全部控制字符（CR/LF/TAB 及其他 C0/C1 控制符），并截断超长输入。
     *
     * @param raw 原始输入，可为 null
     * @return 消毒后的输入；null 原样返回 null
     */
    public static String stripControlChars(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
