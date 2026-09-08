package com.panjia.console.license.controller.auth;

import tools.jackson.databind.ObjectMapper;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.common.util.ClientIpResolver;
import com.panjia.console.license.controller.auth.dto.*;
import com.panjia.console.license.service.ActivateService;
import com.panjia.console.license.service.CheckService;
import com.panjia.console.license.service.HeartbeatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/**
 * 鉴权面 Controller（公网 443，/api/auth/*）
 * <p>
 * 三个核心接口：activate / heartbeat / check
 * <p>
 * ★ §5.7 冻结规则（Code Review 必查）：
 * <ul>
 *   <li>授权判定结果永远走 200 + clientMode</li>
 *   <li>只有"请求本身非法"才用 4xx</li>
 *   <li>签名错误 → 401</li>
 *   <li>并发冲突 → 409</li>
 * </ul>
 * <p>
 * ★ P0-5 铁律：心跳请求不含 clientMode，服务端始终自行计算，
 * 客户端只能执行服务端下发的 clientMode。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ActivateController {

    private final ActivateService activateService;
    private final HeartbeatService heartbeatService;
    private final CheckService checkService;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    /**
     * 激活授权 —— 全系统唯一签发 JWT 的接口
     * <p>
     * 并发控制：SELECT ... FOR UPDATE 锁 t_auth_code 行
     * 锁超时 → CONCURRENT_ACTIVATE(409)，客户端应稍后重试
     *
     * @param request 激活请求
     * @return 激活响应（JWT + offlineExpireAt）
     */
    @PostMapping("/activate")
    public ResponseEntity<ActivateResponse> activate(@Valid @RequestBody ActivateRequest request) {
        log.info("[req={}] [activate] Activate request: authCode=***{}, instanceId={}",
                request.getRequestId(),
                request.getAuthCode() != null && request.getAuthCode().length() > 4
                        ? request.getAuthCode().substring(request.getAuthCode().length() - 4) : "",
                request.getInstanceId());

        try {
            // 事务内完成状态转换和绑定（JWT 签名在事务外）
            ActivateService.ActivateResult result = activateService.activate(
                    request.getAuthCode(),
                    request.getFingerprint(),
                    request.getProductVersion(),
                    request.getCompany(),
                    request.getInstanceId(),
                    request.getRequestId()
            );

            // ★ 事务外签发 JWT（缩短数据库锁持有时间）
            String jwt = activateService.signJwt(result.getClaims());

            ActivateResponse response = ActivateResponse.builder()
                    .jwt(jwt)
                    .offlineExpireAt(result.getOfflineExpireAt())
                    .clientMode("NORMAL")
                    .build();

            return ResponseEntity.ok(response);

        } catch (LicenseException e) {
            // 业务异常由全局异常处理器统一映射
            throw e;
        } catch (org.springframework.dao.CannotAcquireLockException e) {
            // 行锁等待超时 → CONCURRENT_ACTIVATE(409)
            log.warn("Activate lock timeout: authCode=***{}",
                    request.getAuthCode() != null && request.getAuthCode().length() > 4
                            ? request.getAuthCode().substring(request.getAuthCode().length() - 4) : "");
            throw new LicenseException(LicenseErrorCode.CONCURRENT_ACTIVATE, e);
        } catch (org.springframework.dao.QueryTimeoutException e) {
            // lock_timeout 触发
            log.warn("Activate query timeout", e);
            throw new LicenseException(LicenseErrorCode.CONCURRENT_ACTIVATE, e);
        }
    }

    /**
     * 心跳上报
     * <p>
     * ★ 请求不含 clientMode —— 服务端自行计算并返回
     * ★ P0-B：token 剩余寿命低于续签阈值时，响应携带重签后的新 JWT（token 字段）
     *
     * @param authHeader Authorization: Bearer <JWT>
     * @param request    心跳请求
     * @param httpReq    HTTP 请求（用于获取原始 body 诊断）
     * @return 心跳响应（offlineExpireAt + clientMode + token?）
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody HeartbeatRequest request,
            HttpServletRequest httpReq) {

        String jwt = extractJwt(authHeader);

        // 记录原始请求（脱敏）用于诊断
        String rawJson = null;
        try {
            rawJson = objectMapper.writeValueAsString(request);
        } catch (Exception ignored) {
            // ignore
        }

        HeartbeatService.HeartbeatResponse result = heartbeatService.heartbeat(
                jwt,
                request.getInstanceId(),
                request.getFingerprint(),
                request.getReportedAt() != null ? request.getReportedAt() : OffsetDateTime.now(),
                request.getCurrentStores(),
                request.getCurrentUsers(),
                resolveClientIp(httpReq),
                rawJson
        );

        HeartbeatResponse response = HeartbeatResponse.builder()
                .offlineExpireAt(result.getOfflineExpireAt())
                .clientMode(result.getClientMode())
                .code(result.getRestrictCode())
                .token(result.getToken())
                .build();

        // 授权受限也返回 200 + clientMode=RESTRICT（§5.7 冻结规则）
        return ResponseEntity.ok(response);
    }

    /**
     * 实时授权校验
     * <p>
     * ★ 不判断在线/离线状态，不查心跳新鲜度
     * 决策权 100% 在客户端本地
     *
     * @param authHeader Authorization: Bearer <JWT>
     * @param request    校验请求
     * @return 校验结果
     */
    @PostMapping("/check")
    public ResponseEntity<CheckResponse> check(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) CheckRequest request) {

        String jwt = extractJwt(authHeader);

        if (request == null) {
            request = new CheckRequest();
        }

        CheckService.CheckResponse result = checkService.check(
                jwt,
                request.getProductVersion(),
                request.getCurrentStores(),
                request.getCurrentUsers()
        );

        CheckResponse response = CheckResponse.builder()
                .clientMode(result.getClientMode())
                .code(result.getRestrictCode())
                .capabilities(result.getCapabilities())
                .maxStores(result.getMaxStores())
                .maxUsers(result.getMaxUsers())
                .endDate(result.getEndDate())
                .build();

        // 授权受限也返回 200 + clientMode=RESTRICT
        return ResponseEntity.ok(response);
    }

    /**
     * 从 Authorization header 中提取 JWT
     */
    private String extractJwt(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new LicenseException(LicenseErrorCode.SIGNATURE_INVALID);
        }
        return authHeader.substring(7);
    }

    /**
     * ★ H2 安全修复：解析客户端真实 IP
     * <p>
     * 旧实现直接取 X-Forwarded-For 第一个 IP，攻击者可伪造该头部绕过 IP 多实例检测。
     * 新实现委托 ClientIpResolver：从右向左跳过可信代理 CIDR，取第一个非可信代理的 IP。
     */
    private String resolveClientIp(HttpServletRequest req) {
        return clientIpResolver.resolve(req);
    }
}
