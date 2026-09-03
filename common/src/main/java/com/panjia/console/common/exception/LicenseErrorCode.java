package com.panjia.console.common.exception;

/**
 * 授权服务错误码枚举
 * <p>
 * 与客户端 V1.1 对齐，activate/heartbeat/check 三处共用同一套语义。
 * HTTP 状态码映射遵循 §5.7 冻结表：
 * <ul>
 *   <li>授权受限一律 200 + clientMode=RESTRICT</li>
 *   <li>签名错误 → 401</li>
 *   <li>参数非法 → 400</li>
 *   <li>并发冲突 → 409</li>
 *   <li>内部错误 → 500</li>
 * </ul>
 */
public enum LicenseErrorCode {

    // 签名相关（401）
    SIGNATURE_INVALID(401, "SIGNATURE_INVALID", "JWT 签名无效"),

    // 授权受限（200 + RESTRICT）
    FP_MISMATCH(200, "FP_MISMATCH", "指纹不匹配"),
    VERSION_OUT_OF_RANGE(200, "VERSION_OUT_OF_RANGE", "版本不在许可范围"),
    TOKEN_REVOKED(200, "TOKEN_REVOKED", "授权已吊销或版本已过期"),
    AUTH_EXPIRED(200, "AUTH_EXPIRED", "授权已到期"),
    NOT_ACTIVATED(200, "NOT_ACTIVATED", "授权未激活"),

    // 请求非法（400）
    BAD_REQUEST(400, "BAD_REQUEST", "请求参数非法"),
    INVALID_STATUS(400, "INVALID_STATUS", "授权状态不满足前置条件"),
    AUTH_CODE_NOT_FOUND(400, "AUTH_CODE_NOT_FOUND", "授权码不存在"),

    // 并发冲突（409）
    CONCURRENT_ACTIVATE(409, "CONCURRENT_ACTIVATE", "激活并发冲突，请稍后重试"),
    DB_CONFLICT(409, "DB_CONFLICT", "数据库唯一约束冲突"),

    // 内部错误（500）
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务内部错误");

    private final int httpStatus;
    private final String code;
    private final String message;

    LicenseErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 判断是否属于授权受限类错误（返回 200 + RESTRICT，而非 4xx）
     */
    public boolean isRestrictError() {
        return httpStatus == 200 && this != BAD_REQUEST;
    }
}
