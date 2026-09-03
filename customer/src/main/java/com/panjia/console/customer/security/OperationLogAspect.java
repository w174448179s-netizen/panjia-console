package com.panjia.console.customer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.mapper.OpsLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;

/**
 * 操作日志切面
 * <p>
 * 拦截标注 @OpsLog 的 Controller 方法，自动记录操作审计日志到 customer.pj_ops_log 表。
 * <p>
 * 记录内容：操作人、操作类型、目标类型、目标 ID、入参（脱敏）、操作结果、IP、时间。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OpsLogMapper opsLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 环绕通知：拦截 @OpsLog 注解的方法
     */
    @Around("@annotation(opsLogAnnotation)")
    public Object around(ProceedingJoinPoint joinPoint, OpsLog opsLogAnnotation) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String action = opsLogAnnotation.action();
        String targetType = opsLogAnnotation.targetType();

        // 获取请求信息
        String ip = getClientIp();
        String operator = getCurrentOperator();

        // 提取目标 ID（从方法参数中查找 id 或 customerNo 等）
        String targetId = extractTargetId(joinPoint, signature.getParameterNames(), joinPoint.getArgs());

        // 序列化入参（截断，避免过长）
        String params = serializeParams(joinPoint.getArgs());

        long startTime = System.currentTimeMillis();
        String result = "SUCCESS";
        Object returnValue;

        try {
            returnValue = joinPoint.proceed();
            // 检查返回结果是否为失败
            if (returnValue instanceof R<?> r && r.getCode() != 200) {
                result = "FAILED";
            }
        } catch (Throwable e) {
            result = "FAILED";
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            try {
                saveOpsLog(action, targetType, targetId, operator, ip, params, result);
            } catch (Exception e) {
                // 日志写入失败不影响主流程
                log.warn("Failed to save ops log: action={}, error={}", action, e.getMessage());
            }
            log.debug("OpsLog: action={}, targetType={}, targetId={}, result={}, duration={}ms",
                    action, targetType, targetId, result, duration);
        }

        return returnValue;
    }

    /**
     * 保存操作日志
     */
    private void saveOpsLog(String action, String targetType, String targetId,
                            String operator, String ip, String params, String result) {
        com.panjia.console.customer.domain.OpsLog record = new com.panjia.console.customer.domain.OpsLog();
        record.setAction(action);
        record.setTargetType(targetType);
        record.setTargetId(targetId);
        record.setOperator(operator);
        record.setIp(ip);
        record.setParams(params);
        record.setResult(result);
        record.setCreatedAt(OffsetDateTime.now());
        opsLogMapper.insert(record);
    }

    /**
     * 获取客户端 IP
     */
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 获取当前操作人
     * <p>
     * TODO: 接入实际的用户认证体系后，从 SecurityContext 或 Token 中获取。
     * 当前默认从请求头 X-Operator 中读取，便于测试。
     */
    private String getCurrentOperator() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "system";
        }
        String operator = attributes.getRequest().getHeader("X-Operator");
        return operator != null && !operator.isEmpty() ? operator : "system";
    }

    /**
     * 从方法参数中提取目标 ID
     * <p>
     * 优先查找名为 id / customerNo / authCode 的参数。
     */
    private String extractTargetId(ProceedingJoinPoint joinPoint, String[] paramNames, Object[] args) {
        if (paramNames == null || args == null) {
            return null;
        }
        for (int i = 0; i < paramNames.length; i++) {
            String name = paramNames[i];
            if (("id".equals(name) || "customerNo".equals(name) || "authCode".equals(name))
                    && args[i] != null) {
                return String.valueOf(args[i]);
            }
        }
        return null;
    }

    /**
     * 序列化入参（截断到 2000 字符，避免日志表过大）
     */
    private String serializeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            // 只取第一个对象参数（通常是 RequestBody）
            Object bodyArg = null;
            for (Object arg : args) {
                if (arg != null && !arg.getClass().isPrimitive()
                        && !(arg instanceof String)
                        && !(arg instanceof Number)
                        && !(arg instanceof Boolean)) {
                    bodyArg = arg;
                    break;
                }
            }
            if (bodyArg == null) {
                return null;
            }
            String json = objectMapper.writeValueAsString(bodyArg);
            if (json.length() > 2000) {
                json = json.substring(0, 2000) + "...(truncated)";
            }
            return json;
        } catch (Exception e) {
            return "serialize_error";
        }
    }
}
