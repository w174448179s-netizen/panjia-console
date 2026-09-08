package com.panjia.console.license.service;

import com.panjia.console.common.enums.AuthCodeStatus;
import com.panjia.console.common.enums.ClientMode;
import com.panjia.console.common.enums.FingerprintStatus;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.common.util.AuthCodeGenerator;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.domain.FingerprintBinding;
import com.panjia.console.license.domain.LicenseContent;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.mapper.FingerprintBindingMapper;
import com.panjia.console.license.mapper.LicenseContentMapper;
import com.panjia.console.license.security.JwtConfigProperties;
import com.panjia.console.license.security.LicenseJwtClaims;
import com.panjia.console.license.security.SignatureEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 激活服务
 * <p>
 * 全系统唯一签发 JWT 的地方（§4.4）。
 * <p>
 * ★ F2 冻结规则：整个激活是一个原子状态转换。
 * 对 t_auth_code 加 SELECT ... FOR UPDATE，保证同一 authCode 同时只有一个 activate 能完成有效绑定。
 * <p>
 * 锁协议（固定顺序，防死锁）：
 * 1. 先锁 t_auth_code（FOR UPDATE）
 * 2. 再锁/读写 t_fingerprint_binding
 * 3. 读 t_license_content（读已提交即可）
 * 4. 签发 JWT → COMMIT（签名在事务外，缩短锁持有时间）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivateService {

    /**
     * 幂等缓存 TTL（5 分钟）。覆盖客户端网络抖动重试窗口。
     */
    private static final Duration IDEMPOTENT_TTL = Duration.ofMinutes(5);

    /**
     * ★ H3 安全修复：幂等存储委托给 IdempotentStore 接口。
     * 单实例 → LocalIdempotentStore（ConcurrentHashMap）；
     * 集群部署 → 切换为分布式实现（如 Redis），只需实现 IdempotentStore 接口即可。
     */
    private final IdempotentStore idempotentStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final AuthCodeMapper authCodeMapper;
    private final FingerprintBindingMapper fingerprintBindingMapper;
    private final LicenseContentMapper licenseContentMapper;
    private final SignatureEngine signatureEngine;
    private final JwtConfigProperties config;
    private final BlacklistService blacklistService;
    private final AlertService alertService;

    /**
     * 激活授权 —— 签发 JWT 的唯一时机
     * <p>
     * 流程（§4.4）：
     * 1. 锁授权码行
     * 2. 校验状态 + 过期 + 黑名单
     * 3. 锁指纹行 + 三步校验（首次/续期/换机）
     * 4. 读 current license_content
     * 5. 提交事务（释放锁）
     * 6. 事务外签发 JWT
     *
     * @param authCode       授权码
     * @param fingerprint    指纹原文（客户端 sha256(hostMachineId|instanceId) 输出）
     * @param productVersion 产品版本
     * @param company        公司名称（可选）
     * @param instanceId     客户端实例 ID（可选，仅审计用，未持久化）
     * @param requestId      幂等键（可选，UUID v4 推荐）。同一 (authCode, requestId) 重复调用返回同一结果
     * @return 激活结果（JWT + offlineExpireAt）
     */
    public ActivateResult activate(String authCode, String fingerprint, String productVersion,
                                   String company, String instanceId, String requestId) {
        // ★ S-4 修复：低频主动清扫（摊销到请求里，避免单独起定时线程）
        if (idempotentStore.size() > 5_000) {
            idempotentStore.sweepExpired();
        }

        // ★ P1-2：幂等短路。命中已成功记录直接返回，避免重试进入 409 CONCURRENT_ACTIVATE 死循环。
        if (requestId != null && !requestId.isBlank()) {
            String cacheKey = buildIdempotentKey(authCode, requestId);
            String cachedJson = idempotentStore.get(cacheKey);
            if (cachedJson != null) {
                ActivateResult cached = deserializeResult(cachedJson);
                if (cached != null) {
                    log.info("[req={}] [activate] Idempotent hit: authCode={}",
                            requestId, AuthCodeGenerator.mask(authCode));
                    return cached;
                }
                // 反序列化失败 → 清除坏缓存，降级走完整流程
                log.warn("[req={}] [activate] Idempotent cache deserialize failed, falling through", requestId);
                idempotentStore.remove(cacheKey);
            }
        }

        // ★ 自调用事务修复：用 TransactionTemplate 显式包裹事务边界。
        // 旧代码 this.doActivate() 是自调用，Spring AOP 代理不拦截 → @Transactional 失效 →
        // SELECT ... FOR UPDATE 在无事务下运行，行锁提交后立即释放，并发控制形同虚设。
        // TransactionTemplate.execute() 直接通过 PlatformTransactionManager 开启事务，
        // 不依赖 AOP 代理，自调用也能正确启用事务。
        ActivateResult result = transactionTemplate.execute(status ->
                doActivate(authCode, fingerprint, productVersion, company, instanceId, requestId));

        // 仅成功路径写入幂等缓存（H3：通过 IdempotentStore 接口，支持分布式扩展）
        if (requestId != null && !requestId.isBlank()) {
            String cacheKey = buildIdempotentKey(authCode, requestId);
            String serialized = serializeResult(result);
            if (serialized != null) {
                idempotentStore.put(cacheKey, serialized, IDEMPOTENT_TTL);
            }
        }
        return result;
    }

    /**
     * 实际的激活流程
     * <p>
     * ★ 自调用事务修复：事务由 activate() 中的 TransactionTemplate 显式管理，
     * 此方法不再需要 @Transactional 注解（自调用下该注解本就不生效）。
     * 改为 private，防止外部绕过事务直接调用。
     */
    private ActivateResult doActivate(String authCode, String fingerprint, String productVersion,
                                      String company, String instanceId, String requestId) {
        try {
            // 第一步：锁授权码行（★ 并发控制核心）
            AuthCode authCodeEntity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
            if (authCodeEntity == null) {
                throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
            }

            // 第二步：状态校验 —— 必须 ACTIVE 或 REBINDING
            String status = authCodeEntity.getStatus();
            if (!AuthCodeStatus.ACTIVE.name().equals(status)
                    && !AuthCodeStatus.REBINDING.name().equals(status)) {
                // EXPIRED / REVOKED 直接拒绝
                if (AuthCodeStatus.EXPIRED.name().equals(status)) {
                    throw new LicenseException(LicenseErrorCode.AUTH_EXPIRED);
                }
                if (AuthCodeStatus.REVOKED.name().equals(status)) {
                    throw new LicenseException(LicenseErrorCode.TOKEN_REVOKED);
                }
                throw new LicenseException(LicenseErrorCode.INVALID_STATUS);
            }

            // ★ 实时过期判定（不依赖 status 字段，以 end_date 为准）
            if (authCodeEntity.getEndDate() != null
                    && authCodeEntity.getEndDate().isBefore(LocalDate.now())) {
                throw new LicenseException(LicenseErrorCode.AUTH_EXPIRED);
            }

            // ★ H4：黑名单双重校验 —— 即使 status=ACTIVE，只要在黑名单就拒绝
            if (blacklistService.isBlacklisted(authCodeEntity.getId())) {
                throw new LicenseException(LicenseErrorCode.TOKEN_REVOKED);
            }

            Long authCodeId = authCodeEntity.getId();
            // P0-2 修复：客户端已发送 fingerprint = sha256(hostMachineId|instanceId)（即 HardwareFingerprint.calculateHash() 输出）
            //   控制台不再做二次哈希，直接使用客户端传来的哈希作为 fpHash 与服务端对齐
            String fpHash = fingerprint;

            // 第三步：锁 ACTIVE 指纹绑定
            FingerprintBinding activeBinding = fingerprintBindingMapper.selectActiveForUpdate(authCodeId);

            OffsetDateTime now = OffsetDateTime.now();
            boolean isNewBinding = false;

            if (activeBinding == null) {
                // 场景 A：首次激活 / 换机后新激活 → 新建 ACTIVE 绑定
                FingerprintBinding newBinding = new FingerprintBinding();
                newBinding.setAuthCodeId(authCodeId);
                newBinding.setFpHash(fpHash);
                newBinding.setFingerprint(fingerprint);
                newBinding.setProductVersion(productVersion);
                newBinding.setInstanceId(instanceId); // 仅审计，当前未写入 DB 列
                newBinding.setStatus(FingerprintStatus.ACTIVE.name());
                newBinding.setBoundAt(now);
                fingerprintBindingMapper.insert(newBinding);
                isNewBinding = true;

                // 如果是 REBINDING 状态，激活后切回 ACTIVE
                if (AuthCodeStatus.REBINDING.name().equals(status)) {
                    authCodeEntity.setStatus(AuthCodeStatus.ACTIVE.name());
                    authCodeEntity.setUpdatedAt(now);
                    authCodeMapper.updateById(authCodeEntity);
                }

                log.info("[req={}] [activate] New fingerprint binding: authCode={}, instanceId={}",
                        requestId, AuthCodeGenerator.mask(authCode), instanceId);

            } else if (activeBinding.getFpHash().equals(fpHash)) {
                // 场景 B：续期匹配 —— 同一指纹，不做操作（仅刷新）
                log.debug("[req={}] [activate] Fingerprint match (renew): authCode={}, instanceId={}",
                        requestId, AuthCodeGenerator.mask(authCode), instanceId);

            } else {
                // 场景 C：不同指纹 + 已有 ACTIVE 绑定
                // 如果是 REBINDING 状态，允许换机：旧 ACTIVE → INVALIDATED，新建 ACTIVE
                if (AuthCodeStatus.REBINDING.name().equals(status)) {
                    // 失效旧绑定
                    activeBinding.setStatus(FingerprintStatus.INVALIDATED.name());
                    activeBinding.setInvalidatedAt(now);
                    activeBinding.setInvalidateReason("REBIND");
                    activeBinding.setUpdatedAt(now);
                    fingerprintBindingMapper.updateById(activeBinding);

                    // 新建绑定
                    FingerprintBinding newBinding = new FingerprintBinding();
                    newBinding.setAuthCodeId(authCodeId);
                    newBinding.setFpHash(fpHash);
                    newBinding.setInstanceId(instanceId);
                    newBinding.setStatus(FingerprintStatus.ACTIVE.name());
                    newBinding.setBoundAt(now);
                    fingerprintBindingMapper.insert(newBinding);
                    isNewBinding = true;

                    // 状态切回 ACTIVE
                    authCodeEntity.setStatus(AuthCodeStatus.ACTIVE.name());
                    authCodeEntity.setUpdatedAt(now);
                    authCodeMapper.updateById(authCodeEntity);

                    log.info("[req={}] [activate] Fingerprint rebinded: authCode={}, instanceId={}",
                            requestId, AuthCodeGenerator.mask(authCode), instanceId);
                } else {
                    // ACTIVE 状态下指纹不匹配 → 多实例场景，抛出指纹不匹配
                    // 注意：多实例两阶段检测在 HeartbeatService 中处理
                    throw new LicenseException(LicenseErrorCode.FP_MISMATCH);
                }
            }

            // 第四步：读取当前 license_content（不新建，license_version 不变）
            LicenseContent currentContent = licenseContentMapper.selectCurrent(authCodeId);
            if (currentContent == null) {
                // 理论上不会发生（签发时已创建 v1），兜底处理
                throw new LicenseException(LicenseErrorCode.INTERNAL_ERROR);
            }

            // 第五步：计算 offlineExpireAt
            OffsetDateTime offlineExpireAt = now.plusDays(config.getOfflineExpireDays());

            // 更新授权码的 offlineExpireAt（快照）
            authCodeEntity.setOfflineExpireAt(offlineExpireAt);
            authCodeEntity.setUpdatedAt(now);
            authCodeMapper.updateById(authCodeEntity);

            // 第六步：组装 JWT claims（在事务内组装，事务外签名）
            LicenseJwtClaims claims = LicenseJwtClaims.builder()
                    .authCode(authCode)
                    .customerNo(authCodeEntity.getCustomerNo())
                    .company(company != null ? company : authCodeEntity.getCustomerNo())
                    .plan(authCodeEntity.getVersion())
                    .fpHash(fpHash)
                    .maxStores(currentContent.getMaxStores())
                    .maxUsers(currentContent.getMaxUsers())
                    .capabilities(parseCapabilities(currentContent.getCapabilities()))
                    .startDate(currentContent.getStartDate())
                    .endDate(currentContent.getEndDate())
                    .maintenanceEndDate(currentContent.getMaintenanceEndDate())
                    .minSupportedVersion(currentContent.getMinSupportedVersion())
                    .maxSupportedVersion(currentContent.getMaxSupportedVersion())
                    .keyVersion(currentContent.getKeyVersion())
                    .licenseVersion(currentContent.getLicenseVersion())
                    .clientMode(ClientMode.NORMAL.name())
                    .offlineExpireAt(offlineExpireAt)
                    .issuedAt(now)
                    .build();

            // ★ 注意：JWT 签名在事务外执行（见下方 activateWithJwtSigning）
            // 这里返回 claims 供外层签名，缩短数据库锁持有时间
            return new ActivateResult(claims, offlineExpireAt, isNewBinding);

        } catch (DuplicateKeyException e) {
            // partial unique index 兜底触发（业务层行锁已规避，但极端并发仍可能命中）
            log.warn("[req={}] [activate] DB conflict on activate: authCode={}", requestId, AuthCodeGenerator.mask(authCode));
            throw new LicenseException(LicenseErrorCode.DB_CONFLICT, e);
        }
        // 注意：LockTimeoutException 等由上层统一捕获映射为 CONCURRENT_ACTIVATE
    }

    /**
     * 幂等缓存 key。包含 authCode 防止不同授权码之间串扰。
     */
    private String buildIdempotentKey(String authCode, String requestId) {
        return authCode + "|" + requestId;
    }

    /**
     * ★ H3 修复：序列化 ActivateResult 为 JSON，供 IdempotentStore 存储。
     * 使用 Spring 注入的 ObjectMapper（已注册 JavaTimeModule，支持 OffsetDateTime）。
     */
    private String serializeResult(ActivateResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Failed to serialize ActivateResult for idempotent cache", e);
            return null;
        }
    }

    /**
     * ★ H3 修复：从 JSON 反序列化 ActivateResult。
     */
    private ActivateResult deserializeResult(String json) {
        try {
            return objectMapper.readValue(json, ActivateResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ActivateResult from idempotent cache", e);
            return null;
        }
    }

    /**
     * 签发 JWT（在事务外调用，缩短锁持有时间）
     *
     * @param claims JWT 载荷
     * @return JWT 字符串
     */
    public String signJwt(LicenseJwtClaims claims) {
        return signatureEngine.issueJwt(claims);
    }

    /**
     * 激活结果
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ActivateResult {
        private LicenseJwtClaims claims;
        private OffsetDateTime offlineExpireAt;
        private boolean isNewBinding;
    }

    /**
     * 解析 capabilities JSON 字符串为列表
     */
    private List<String> parseCapabilities(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(capabilitiesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("Failed to parse capabilities: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
