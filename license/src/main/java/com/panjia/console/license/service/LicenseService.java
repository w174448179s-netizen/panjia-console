package com.panjia.console.license.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.panjia.console.common.enums.AuthCodeStatus;
import com.panjia.console.common.enums.BlacklistReason;
import com.panjia.console.common.enums.FingerprintStatus;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.common.util.AuthCodeGenerator;
import com.panjia.console.license.api.dto.CreateLicenseRequest;
import com.panjia.console.license.api.dto.CreateLicenseResult;
import com.panjia.console.license.api.dto.RenewLicenseRequest;
import com.panjia.console.license.api.dto.RestoreResult;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.domain.Blacklist;
import com.panjia.console.license.domain.FingerprintBinding;
import com.panjia.console.license.domain.LicenseContent;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.mapper.BlacklistMapper;
import com.panjia.console.license.mapper.FingerprintBindingMapper;
import com.panjia.console.license.mapper.LicenseContentMapper;
import com.panjia.console.license.security.SignatureEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 授权管理服务
 * <p>
 * 负责授权的签发、吊销、恢复、换机等核心状态变更操作。
 * <p>
 * ★ 关键规则（Code Review 必查）：
 * <ul>
 *   <li>F1：签发必须幂等 —— 同一 requestId 重试返回同一授权</li>
 *   <li>restore 事务内必须同时删除 REVOKE 黑名单，避免"status=ACTIVE 却仍有 REVOKE 黑名单"的歧义</li>
 *   <li>换机与 activate 锁顺序一致：先 auth_code 再 fingerprint</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {

    private final AuthCodeMapper authCodeMapper;
    private final LicenseContentMapper licenseContentMapper;
    private final FingerprintBindingMapper fingerprintBindingMapper;
    private final BlacklistMapper blacklistMapper;
    private final SignatureEngine signatureEngine;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    /**
     * 签发授权（幂等）
     * <p>
     * ★ F1 冻结规则：同一 requestId 重复调用必须返回同一个授权，绝不重复创建。
     * <p>
     * 流程（§4.1）：
     * 1. 按 requestId 加行锁查询
     * 2. 命中 → 直接返回已有结果
     * 3. 未命中 → 生成 authCode + licenseId，插入 t_auth_code + t_license_content(v1)
     *
     * @param req 签发请求
     * @return 签发结果
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public CreateLicenseResult createLicense(CreateLicenseRequest req) {
        // 第一步：按 requestId 加行锁查询（幂等核心）
        AuthCode existing = authCodeMapper.selectByRequestIdForUpdate(req.getRequestId());
        if (existing != null) {
            // ★ 命中幂等键 → 直接返回首次创建的结果，绝不重新生成
            log.info("createLicense idempotent hit: requestId={}, authCode={}",
                    req.getRequestId(), AuthCodeGenerator.mask(existing.getAuthCode()));
            return buildCreateResult(existing);
        }

        // 第二步：生成授权码和 licenseId
        String authCode = AuthCodeGenerator.generate();
        String licenseId = AuthCodeGenerator.generateLicenseId();
        int keyVersion = signatureEngine.getCurrentKeyVersion();

        try {
            // 第三步：插入授权码记录
            AuthCode authCodeEntity = new AuthCode();
            authCodeEntity.setAuthCode(authCode);
            authCodeEntity.setCustomerNo(req.getCustomerNo());
            authCodeEntity.setLicenseType(req.getLicenseType());
            authCodeEntity.setVersion(req.getVersion());
            authCodeEntity.setMaxStores(req.getMaxStores());
            authCodeEntity.setMaxUsers(req.getMaxUsers());
            authCodeEntity.setCapabilities(toJson(req.getCapabilities()));
            authCodeEntity.setStartDate(req.getStartDate());
            authCodeEntity.setEndDate(req.getEndDate());
            authCodeEntity.setMaintenanceEndDate(req.getMaintenanceEndDate());
            authCodeEntity.setMinSupportedVersion(req.getMinSupportedVersion());
            authCodeEntity.setMaxSupportedVersion(req.getMaxSupportedVersion());
            authCodeEntity.setStatus(AuthCodeStatus.ACTIVE.name());
            authCodeEntity.setIsTest("TEST".equals(req.getLicenseType()));
            authCodeEntity.setRequestId(req.getRequestId());
            authCodeEntity.setIssuedBy(req.getIssuedBy());
            authCodeEntity.setIssuedAt(OffsetDateTime.now());
            authCodeMapper.insert(authCodeEntity);

            // 第四步：插入 license_content v1（签发时创建，版本从 1 起）
            LicenseContent content = new LicenseContent();
            content.setAuthCodeId(authCodeEntity.getId());
            content.setLicenseVersion(1);
            content.setVersion(req.getVersion());
            content.setMaxStores(req.getMaxStores());
            content.setMaxUsers(req.getMaxUsers());
            content.setCapabilities(toJson(req.getCapabilities()));
            content.setStartDate(req.getStartDate());
            content.setEndDate(req.getEndDate());
            content.setMaintenanceEndDate(req.getMaintenanceEndDate());
            content.setMinSupportedVersion(req.getMinSupportedVersion());
            content.setMaxSupportedVersion(req.getMaxSupportedVersion());
            content.setKeyVersion(keyVersion);
            content.setIsCurrent(true);
            content.setEffectiveAt(OffsetDateTime.now());
            licenseContentMapper.insert(content);

            log.info("License created: authCode={}, customerNo={}, licenseId={}",
                    AuthCodeGenerator.mask(authCode), req.getCustomerNo(), licenseId);

            return CreateLicenseResult.builder()
                    .authCode(authCode)
                    .licenseId(licenseId)
                    .licenseVersion(1)
                    .build();

        } catch (DuplicateKeyException e) {
            // ★ 兜底：极端并发下 partial unique index / request_id UNIQUE 冲突
            // → 二次查询返回已有结果（保证幂等最终一致）
            log.warn("DuplicateKey on createLicense, retrying query: requestId={}", req.getRequestId());
            AuthCode retryEntity = authCodeMapper.selectByRequestIdForUpdate(req.getRequestId());
            if (retryEntity != null) {
                return buildCreateResult(retryEntity);
            }
            // 二次查询也没有 → 不是幂等冲突，是其他唯一约束冲突，抛出
            throw new LicenseException(LicenseErrorCode.DB_CONFLICT, e);
        }
    }

    /**
     * 吊销授权
     * <p>
     * 原子操作：
     * 1. status: ACTIVE → REVOKED
     * 2. 写入 t_blacklist(reason=REVOKE)
     *
     * @param authCode 授权码
     * @param reason   吊销原因
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void revoke(String authCode, String reason) {
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 已吊销的直接返回（幂等）
        if (AuthCodeStatus.REVOKED.name().equals(entity.getStatus())) {
            log.warn("License already revoked: authCode={}", AuthCodeGenerator.mask(authCode));
            return;
        }

        // 更新授权状态
        entity.setStatus(AuthCodeStatus.REVOKED.name());
        entity.setRevokedAt(OffsetDateTime.now());
        entity.setRevokedReason(reason);
        entity.setUpdatedAt(OffsetDateTime.now());
        authCodeMapper.updateById(entity);

        // 写入/更新黑名单（REVOKE 原因）
        // t_blacklist.auth_code_id 是 UNIQUE，已存在记录（如 MULTI_INSTANCE）时更新 reason 为 REVOKE
        Blacklist existingBl = blacklistMapper.selectByAuthCodeId(entity.getId());
        if (existingBl != null) {
            existingBl.setReason(BlacklistReason.REVOKE.name());
            existingBl.setCreatedBy("system");
            blacklistMapper.updateById(existingBl);
        } else {
            Blacklist blacklist = new Blacklist();
            blacklist.setAuthCodeId(entity.getId());
            blacklist.setReason(BlacklistReason.REVOKE.name());
            blacklist.setCreatedBy("system");
            blacklistMapper.insert(blacklist);
        }

        // 记录告警
        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "LICENSE_REVOKED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "WARN",
                "授权已吊销",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\"}",
                        AuthCodeGenerator.mask(authCode), reason)
        );

        log.info("License revoked: authCode={}, reason={}", AuthCodeGenerator.mask(authCode), reason);
    }

    /**
     * 恢复授权（旧 JWT 失效）
     * <p>
     * ★ 原子完成以下动作（同一事务，§4.2）：
     * 1. status: REVOKED → ACTIVE
     * 2. 删除 t_blacklist（reason=REVOKE）记录
     * 3. 新增 t_license_content（license_version +1, is_current=TRUE）
     * <p>
     * 关键：restore 必须让 t_blacklist(REVOKE) 与 auth_code.status=ACTIVE 不会同时存在。
     *
     * @param authCode 授权码
     * @param reason   恢复原因
     * @return 恢复结果（含新版本号）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public RestoreResult restore(String authCode, String reason) {
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 前置校验：仅 REVOKED 状态可恢复
        if (!AuthCodeStatus.REVOKED.name().equals(entity.getStatus())) {
            throw new IllegalStateException("仅已吊销的授权可恢复，当前状态：" + entity.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 1. 状态切换：REVOKED → ACTIVE
        entity.setStatus(AuthCodeStatus.ACTIVE.name());
        entity.setRevokedAt(null);
        entity.setRevokedReason(null);
        entity.setUpdatedAt(now);
        authCodeMapper.updateById(entity);

        // 2. ★ 删除 REVOKE 黑名单（必须与状态切换在同一事务）
        //    历史操作已记录在 ops_log，无需保留黑名单
        Blacklist existingBl = blacklistMapper.selectByAuthCodeId(entity.getId());
        if (existingBl != null && BlacklistReason.REVOKE.name().equals(existingBl.getReason())) {
            blacklistMapper.deleteById(existingBl.getId());
        }

        // 3. 新增 license_content（版本 +1，旧 JWT 立即失效）
        LicenseContent current = licenseContentMapper.selectCurrent(entity.getId());
        // 用 max(license_version) 计算新版本，避免 current 为 null 时与已存在版本冲突
        int maxVersion = licenseContentMapper.selectMaxVersion(entity.getId());
        int newVersion = maxVersion + 1;
        int keyVersion = signatureEngine.getCurrentKeyVersion();

        // ★ 先将旧版本置为非当前，再插入新版本（避免违反 is_current=TRUE 的 partial unique index）
        if (current != null) {
            current.setIsCurrent(false);
            current.setExpiredAt(now);
            current.setUpdatedAt(now);
            licenseContentMapper.updateById(current);
        }

        LicenseContent newContent = new LicenseContent();
        newContent.setAuthCodeId(entity.getId());
        newContent.setLicenseVersion(newVersion);
        newContent.setVersion(entity.getVersion());
        newContent.setMaxStores(entity.getMaxStores());
        newContent.setMaxUsers(entity.getMaxUsers());
        newContent.setCapabilities(entity.getCapabilities());
        newContent.setStartDate(entity.getStartDate());
        newContent.setEndDate(entity.getEndDate());
        newContent.setMaintenanceEndDate(entity.getMaintenanceEndDate());
        newContent.setMinSupportedVersion(entity.getMinSupportedVersion());
        newContent.setMaxSupportedVersion(entity.getMaxSupportedVersion());
        newContent.setKeyVersion(keyVersion);
        newContent.setIsCurrent(true);
        newContent.setEffectiveAt(now);
        licenseContentMapper.insert(newContent);

        // 记录告警
        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "LICENSE_RESTORED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "INFO",
                "授权已恢复",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\",\"newVersion\":%d}",
                        AuthCodeGenerator.mask(authCode), reason, newVersion)
        );

        log.info("License restored: authCode={}, newVersion={}", AuthCodeGenerator.mask(authCode), newVersion);

        return RestoreResult.builder()
                .licenseVersion(newVersion)
                .restoredAt(now)
                .build();
    }

    /**
     * 续期授权（不新增版本，旧 JWT 不失效）
     * <p>
     * 更新 t_auth_code 与当前 t_license_content 的授权参数。
     * license_version 不变，客户端持有的 JWT 继续有效；
     * 客户端下次 check 时通过响应获取最新配额。
     * <p>
     * 前置：授权码存在且状态非 REVOKED（已吊销需先恢复）。
     *
     * @param req 续期请求
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void renew(RenewLicenseRequest req) {
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(req.getAuthCode());
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 已吊销的授权不能续期，需先恢复
        if (AuthCodeStatus.REVOKED.name().equals(entity.getStatus())) {
            throw new IllegalStateException("已吊销的授权不能续期，请先恢复");
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 1. 更新 t_auth_code（仅更新传入的非空字段）
        if (req.getEndDate() != null) {
            entity.setEndDate(req.getEndDate());
        }
        if (req.getVersion() != null) {
            entity.setVersion(req.getVersion());
        }
        if (req.getMaxStores() != null) {
            entity.setMaxStores(req.getMaxStores());
        }
        if (req.getMaxUsers() != null) {
            entity.setMaxUsers(req.getMaxUsers());
        }
        if (req.getCapabilities() != null) {
            entity.setCapabilities(toJson(req.getCapabilities()));
        }
        if (req.getMaintenanceEndDate() != null) {
            entity.setMaintenanceEndDate(req.getMaintenanceEndDate());
        }
        entity.setUpdatedAt(now);
        authCodeMapper.updateById(entity);

        // 2. 同步更新当前 t_license_content（check 接口从这里读 capabilities/配额）
        LicenseContent current = licenseContentMapper.selectCurrent(entity.getId());
        if (current != null) {
            if (req.getEndDate() != null) {
                current.setEndDate(req.getEndDate());
            }
            if (req.getVersion() != null) {
                current.setVersion(req.getVersion());
            }
            if (req.getMaxStores() != null) {
                current.setMaxStores(req.getMaxStores());
            }
            if (req.getMaxUsers() != null) {
                current.setMaxUsers(req.getMaxUsers());
            }
            if (req.getCapabilities() != null) {
                current.setCapabilities(toJson(req.getCapabilities()));
            }
            if (req.getMaintenanceEndDate() != null) {
                current.setMaintenanceEndDate(req.getMaintenanceEndDate());
            }
            current.setUpdatedAt(now);
            licenseContentMapper.updateById(current);
        }

        // 3. 记录告警
        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "LICENSE_RENEWED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "INFO",
                "授权已续期",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\",\"endDate\":\"%s\"}",
                        AuthCodeGenerator.mask(req.getAuthCode()),
                        req.getReason() != null ? req.getReason() : "",
                        req.getEndDate())
        );

        log.info("License renewed: authCode={}, endDate={}",
                AuthCodeGenerator.mask(req.getAuthCode()), req.getEndDate());
    }

    /**
     * 换机（使当前指纹失效，授权进入 REBINDING 状态）
     * <p>
     * ★ F2 冻结规则：必须先 SELECT ... FOR UPDATE 锁住 t_auth_code 行，
     * 锁顺序固定为「先 t_auth_code，再 t_fingerprint_binding」，防止死锁。
     *
     * @param authCode 授权码
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void invalidateFingerprint(String authCode) {
        // 第一步：锁授权码行（与 activate 同一把锁，保证并发串行化）
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 前置校验：只有 ACTIVE 或 REBINDING 状态可换机
        if (!AuthCodeStatus.ACTIVE.name().equals(entity.getStatus())
                && !AuthCodeStatus.REBINDING.name().equals(entity.getStatus())) {
            throw new IllegalStateException("仅 ACTIVE 或 REBINDING 状态可换机，当前状态：" + entity.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 第二步：锁并失效当前 ACTIVE 绑定
        FingerprintBinding activeBinding = fingerprintBindingMapper.selectActiveForUpdate(entity.getId());
        if (activeBinding != null) {
            activeBinding.setStatus(FingerprintStatus.INVALIDATED.name());
            activeBinding.setInvalidatedAt(now);
            activeBinding.setInvalidateReason("REBIND");
            activeBinding.setUpdatedAt(now);
            fingerprintBindingMapper.updateById(activeBinding);
        }

        // 第三步：授权状态 → REBINDING
        if (!AuthCodeStatus.REBINDING.name().equals(entity.getStatus())) {
            entity.setStatus(AuthCodeStatus.REBINDING.name());
            entity.setUpdatedAt(now);
            authCodeMapper.updateById(entity);
        }

        // 记录告警
        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "FINGERPRINT_INVALIDATED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "WARN",
                "指纹已失效（换机）",
                String.format("{\"authCode\":\"%s\"}", AuthCodeGenerator.mask(authCode))
        );

        log.info("Fingerprint invalidated (rebind): authCode={}", AuthCodeGenerator.mask(authCode));
    }

    /**
     * 取消换机（运营手动退出 REBINDING 状态）
     * <p>
     * ★ V1.4 新增（M3）：客户放弃换机时，把授权从 REBINDING 切回 ACTIVE。
     * <p>
     * 指纹处理三选一（互斥，先锁 auth_code 再锁 fingerprint，与 activate/rebind 同序）：
     * a) 存在 ACTIVE 绑定 → 保留不动
     * b) 无 ACTIVE 但有 INVALIDATED → 恢复最近一条 INVALIDATED 为 ACTIVE
     * c) 均无（异常态）→ 告警 T3_INTEGRITY + 抛出异常
     * <p>
     * 并清理该 authCode 的 t_multi_instance_pending；不动 t_blacklist / license_content。
     *
     * @param authCode 授权码
     * @param reason   取消原因
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void cancelRebinding(String authCode, String reason) {
        // 第一步：锁授权码行（与 activate/rebind 同一把锁）
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 前置校验：仅 REBINDING 状态可取消换机
        if (!AuthCodeStatus.REBINDING.name().equals(entity.getStatus())) {
            throw new IllegalStateException("仅 REBINDING 状态可取消换机，当前状态：" + entity.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 第二步：指纹处理（三选一，互斥）
        FingerprintBinding activeBinding = fingerprintBindingMapper.selectActiveForUpdate(entity.getId());

        if (activeBinding != null) {
            // a) 存在 ACTIVE 绑定 → 保留不动（换机尚未真正失效旧指纹）
            log.info("cancelRebinding: active binding exists, keeping as-is: authCodeId={}", entity.getId());
        } else {
            // 查最近一条 INVALIDATED
            FingerprintBinding latestInvalidated = fingerprintBindingMapper.selectLatestInvalidated(entity.getId());
            if (latestInvalidated != null) {
                // b) 恢复最近一条 INVALIDATED 为 ACTIVE
                latestInvalidated.setStatus(FingerprintStatus.ACTIVE.name());
                latestInvalidated.setInvalidatedAt(null);
                latestInvalidated.setInvalidateReason(null);
                latestInvalidated.setUpdatedAt(now);
                fingerprintBindingMapper.updateById(latestInvalidated);
                log.info("cancelRebinding: restored latest INVALIDATED binding: authCodeId={}", entity.getId());
            } else {
                // c) 异常态：既无 ACTIVE 也无 INVALIDATED
                // → 写告警 T3_INTEGRITY + 抛出异常，不强行切 ACTIVE
                alertService.createAlert(
                        entity.getCustomerNo(),
                        entity.getId(),
                        "CANCEL_REBIND_INTEGRITY_ERROR",
                        com.panjia.console.common.enums.AlertTrigger.T3_INTEGRITY.name(),
                        "ERROR",
                        "取消换机时发现指纹完整性异常",
                        String.format("{\"authCode\":\"%s\",\"reason\":\"no binding found\"}",
                                AuthCodeGenerator.mask(authCode))
                );
                throw new IllegalStateException("取消换机失败：未找到任何指纹绑定记录，需人工排查");
            }
        }

        // 第三步：清理多实例 pending 记录（取消换机 = 放弃本次差异）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.panjia.console.license.domain.MultiInstancePending> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(com.panjia.console.license.domain.MultiInstancePending::getAuthCodeId, entity.getId());
        com.panjia.console.license.mapper.MultiInstancePendingMapper pendingMapper =
                getPendingMapper();
        pendingMapper.delete(wrapper);

        // 第四步：状态切回 ACTIVE
        entity.setStatus(AuthCodeStatus.ACTIVE.name());
        entity.setUpdatedAt(now);
        authCodeMapper.updateById(entity);

        // 记录告警（服务端决策）
        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "REBINDING_CANCELLED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "INFO",
                "已取消换机",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\"}",
                        AuthCodeGenerator.mask(authCode), reason)
        );

        log.info("Rebinding cancelled: authCode={}", AuthCodeGenerator.mask(authCode));
    }

    // ---- 私有方法 ----

    /**
     * 从 AuthCode 实体构建 CreateLicenseResult
     */
    private CreateLicenseResult buildCreateResult(AuthCode entity) {
        LicenseContent current = licenseContentMapper.selectCurrent(entity.getId());
        int version = current != null ? current.getLicenseVersion() : 1;

        // licenseId 从 requestId 派生（保持幂等）
        String licenseId = "L" + entity.getRequestId().replace("-", "").substring(0, 12).toUpperCase();

        return CreateLicenseResult.builder()
                .authCode(entity.getAuthCode())
                .licenseId(licenseId)
                .licenseVersion(version)
                .build();
    }

    /**
     * 转换对象为 JSON 字符串
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JacksonException e) {
            log.error("Failed to serialize to JSON", e);
            return null;
        }
    }

    /**
     * 获取 MultiInstancePendingMapper（通过 Spring 上下文获取，避免循环依赖）
     * 注：实际项目中建议直接 @Autowired，此处为演示结构
     */
    private com.panjia.console.license.mapper.MultiInstancePendingMapper getPendingMapper() {
        return pendingMapperHolder;
    }

    // 通过字段注入避免循环依赖
    @org.springframework.beans.factory.annotation.Autowired
    private com.panjia.console.license.mapper.MultiInstancePendingMapper pendingMapperHolder;
}
