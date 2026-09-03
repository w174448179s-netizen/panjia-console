package com.panjia.console.common.exception;

import lombok.Getter;

/**
 * 授权服务业务异常
 * <p>
 * 所有鉴权面（activate/heartbeat/check）的业务异常统一使用此类，
 * 由全局异常处理器映射到对应 HTTP 状态码和错误码。
 */
@Getter
public class LicenseException extends RuntimeException {

    private final LicenseErrorCode errorCode;

    public LicenseException(LicenseErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public LicenseException(LicenseErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LicenseException(LicenseErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
