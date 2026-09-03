package com.panjia.console.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解
 * <p>
 * 标注在需要记录审计日志的管理面 Controller 方法上。
 * 由 OperationLogAspect 切面统一处理日志写入。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpsLog {

    /** 操作类型，如 ISSUE、REVOKE、RESTORE、REBIND 等 */
    String action();

    /** 目标类型，如 AUTH_CODE、CUSTOMER、BLACKLIST 等 */
    String targetType() default "";
}
