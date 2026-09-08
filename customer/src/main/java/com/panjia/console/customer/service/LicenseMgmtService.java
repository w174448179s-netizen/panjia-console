package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panjia.console.common.util.TimeUtils;
import com.panjia.console.customer.domain.AuthIssueRecord;
import com.panjia.console.customer.mapper.AuthIssueRecordMapper;
import com.panjia.console.license.api.LicenseEngine;
import com.panjia.console.license.api.dto.CreateLicenseRequest;
import com.panjia.console.license.api.dto.CreateLicenseResult;
import com.panjia.console.license.api.dto.RenewLicenseRequest;
import com.panjia.console.license.api.dto.RestoreResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 授权管理服务
 * <p>
 * 通过 LicenseEngine 接口调用 license 模块能力，
 * 本地仅维护签发流水投影表，license 是权威源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseMgmtService {

    private final LicenseEngine licenseEngine;
    private final AuthIssueRecordMapper authIssueRecordMapper;

    /**
     * 签发授权
     *
     * @param req 签发请求
     * @return 签发结果
     */
    public CreateLicenseResult issueLicense(CreateLicenseRequest req) {
        log.info("Issuing license: customerNo={}, licenseType={}, requestId={}",
                req.getCustomerNo(), req.getLicenseType(), req.getRequestId());

        CreateLicenseResult result = licenseEngine.createLicense(req);

        // 写入本地签发流水投影表
        AuthIssueRecord record = new AuthIssueRecord();
        record.setLicenseId(result.getLicenseId());
        record.setCustomerNo(req.getCustomerNo());
        record.setAuthCode(result.getAuthCode());
        record.setLicenseType(req.getLicenseType());
        record.setVersion(req.getVersion());
        record.setMaxStores(req.getMaxStores());
        record.setMaxUsers(req.getMaxUsers());
        if (req.getCapabilities() != null) {
            record.setCapabilities(String.join(",", req.getCapabilities()));
        }
        record.setStartDate(req.getStartDate());
        record.setEndDate(req.getEndDate());
        record.setMaintenanceEndDate(req.getMaintenanceEndDate());
        record.setOperator(req.getIssuedBy());
        record.setIssueAt(TimeUtils.now());
        authIssueRecordMapper.insert(record);

        return result;
    }

    /**
     * 吊销授权
     *
     * @param authCode 授权码
     * @param reason   吊销原因
     */
    public void revokeLicense(String authCode, String reason) {
        log.info("Revoking license: authCode={}, reason={}", authCode, reason);
        licenseEngine.revoke(authCode, reason);
    }

    /**
     * 恢复授权
     *
     * @param authCode 授权码
     * @param reason   恢复原因
     * @return 恢复结果
     */
    public RestoreResult restoreLicense(String authCode, String reason) {
        log.info("Restoring license: authCode={}, reason={}", authCode, reason);
        return licenseEngine.restore(authCode, reason);
    }

    /**
     * 续期授权
     * <p>
     * 续期会更新 t_auth_code 与 t_license_content，同时同步更新签发流水投影表，
     * 否则授权管理页面展示的到期日期/配额等字段不会变化。
     *
     * @param req 续期请求
     */
    public void renewLicense(RenewLicenseRequest req) {
        log.info("Renewing license: authCode={}, endDate={}", req.getAuthCode(), req.getEndDate());
        licenseEngine.renew(req);

        // 同步更新投影表（end_date / version / max_stores / max_users / capabilities / maintenance_end_date）
        AuthIssueRecord record = authIssueRecordMapper.selectOne(
                new LambdaQueryWrapper<AuthIssueRecord>()
                        .eq(AuthIssueRecord::getAuthCode, req.getAuthCode()));
        if (record != null) {
            if (req.getEndDate() != null) {
                record.setEndDate(req.getEndDate());
            }
            if (req.getVersion() != null) {
                record.setVersion(req.getVersion());
            }
            if (req.getMaxStores() != null) {
                record.setMaxStores(req.getMaxStores());
            }
            if (req.getMaxUsers() != null) {
                record.setMaxUsers(req.getMaxUsers());
            }
            if (req.getCapabilities() != null && !req.getCapabilities().isEmpty()) {
                try {
                    record.setCapabilities(new ObjectMapper().writeValueAsString(req.getCapabilities()));
                } catch (Exception e) {
                    log.warn("Failed to serialize capabilities for projection: {}", e.getMessage());
                }
            }
            if (req.getMaintenanceEndDate() != null) {
                record.setMaintenanceEndDate(req.getMaintenanceEndDate());
            }
            authIssueRecordMapper.updateById(record);
            log.info("Projection updated: authCode={}", req.getAuthCode());
        } else {
            log.warn("Projection record not found for authCode={}, skip sync", req.getAuthCode());
        }
    }

    /**
     * 换机（使当前指纹失效）
     *
     * @param authCode 授权码
     */
    public void invalidateFingerprint(String authCode) {
        log.info("Invalidating fingerprint: authCode={}", authCode);
        licenseEngine.invalidateFingerprint(authCode);
    }

    /**
     * 取消换机
     *
     * @param authCode 授权码
     * @param reason   取消原因
     */
    public void cancelRebinding(String authCode, String reason) {
        log.info("Canceling rebinding: authCode={}, reason={}", authCode, reason);
        licenseEngine.cancelRebinding(authCode, reason);
    }

    /**
     * 从黑名单移除
     *
     * @param authCode 授权码
     */
    public void removeFromBlacklist(String authCode) {
        log.info("Removing from blacklist: authCode={}", authCode);
        licenseEngine.removeFromBlacklist(authCode);
    }

    /**
     * 分页查询签发流水
     * <p>
     * 联表 auth.t_auth_code 取授权当前状态（投影表不存状态，license 是权威源）。
     *
     * @param pageNum    页码
     * @param pageSize   每页大小
     * @param customerNo 客户编号（可选）
     * @return 分页结果
     */
    public IPage<AuthIssueRecord> pageIssueRecords(int pageNum, int pageSize, String customerNo) {
        return authIssueRecordMapper.pageIssueRecordsWithStatus(
                new Page<>(pageNum, pageSize), customerNo);
    }
}
