package com.panjia.console.config;

import com.panjia.console.common.dto.AuthErrorResponse;
import com.panjia.console.common.enums.ClientMode;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;

/**
 * 授权服务全局异常处理器
 * <p>
 * 统一处理鉴权面（/api/auth/*）和管理面的异常。
 * <p>
 * ★ 铁律（Code Review 必查，§5.6 / §5.7）：
 * <ul>
 *   <li>授权受限一律返回 200 + clientMode=RESTRICT，绝不使用 401/403</li>
 *   <li>签名错误 → 401</li>
 *   <li>参数非法 → 400</li>
 *   <li>并发冲突 → 409</li>
 *   <li>内部错误 → 500，不外泄堆栈</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class LicenseExceptionHandler {

    /**
     * 处理授权业务异常
     */
    @ExceptionHandler(LicenseException.class)
    public ResponseEntity<AuthErrorResponse> handleLicenseException(LicenseException ex, HttpServletRequest request) {
        LicenseErrorCode code = ex.getErrorCode();
        log.warn("License exception at [{}]: code={}, message={}",
                request.getRequestURI(), code.getCode(), ex.getMessage());

        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(code.getCode())
                .message(code.getMessage())
                .build();

        // 授权受限类错误 → 200 + RESTRICT
        if (code.isRestrictError()) {
            body.setClientMode(ClientMode.RESTRICT.name());
            return ResponseEntity.ok(body);
        }

        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    /**
     * 处理参数校验异常 → 400 BAD_REQUEST
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<AuthErrorResponse> handleValidationException(Exception ex, HttpServletRequest request) {
        log.warn("Validation exception at [{}]: {}", request.getRequestURI(), ex.getMessage());
        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(LicenseErrorCode.BAD_REQUEST.getCode())
                .message("参数校验失败")
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 处理唯一约束冲突（partial unique index 兜底触发）
     * <p>
     * 签发幂等场景由业务层二次查询处理；激活场景走 DB_CONFLICT(409)。
     * 此处兜底映射为 409，避免原始 SQLException 外泄。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<AuthErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        log.warn("Duplicate key at [{}]: {}", request.getRequestURI(), ex.getMessage());
        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(LicenseErrorCode.DB_CONFLICT.getCode())
                .message(LicenseErrorCode.DB_CONFLICT.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 处理非法状态异常 → 400
     * <p>
     * ★ S-7 修复：响应不回传 ex.getMessage()（可能含内部实现细节），
     * 详细信息只进日志。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AuthErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state at [{}]: {}", request.getRequestURI(), ex.getMessage());
        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(LicenseErrorCode.INVALID_STATUS.getCode())
                .message(LicenseErrorCode.INVALID_STATUS.getMessage())
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 处理非法参数异常 → 400
     * <p>
     * ★ S-7 修复：响应不回传 ex.getMessage()，统一返回通用文案，
     * 防止内部异常信息（类名/SQL 片段/文件路径等）外泄。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AuthErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument at [{}]: {}", request.getRequestURI(), ex.getMessage());
        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(LicenseErrorCode.BAD_REQUEST.getCode())
                .message("参数非法")
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 兜底：所有其他异常 → 500 INTERNAL_ERROR
     * <p>
     * ★ 禁止把 SQLException、堆栈、authCode 明文直接返回客户端。
     * 内部错误只记日志，响应一律 INTERNAL_ERROR。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AuthErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at [{}]", request.getRequestURI(), ex);
        AuthErrorResponse body = AuthErrorResponse.builder()
                .code(LicenseErrorCode.INTERNAL_ERROR.getCode())
                .message(LicenseErrorCode.INTERNAL_ERROR.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
