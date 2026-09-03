package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.AuthIssueRecord;
import com.panjia.console.customer.service.LicenseMgmtService;
import com.panjia.console.license.api.dto.CreateLicenseRequest;
import com.panjia.console.license.api.dto.CreateLicenseResult;
import com.panjia.console.license.api.dto.RestoreResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 授权管理 Controller
 * <p>
 * 管理面接口，负责授权的签发、吊销、恢复、换机等运营操作。
 * 通过 LicenseEngine 接口与 license 模块交互。
 */
@RestController
@RequestMapping("/api/v1/license-mgmt")
@RequiredArgsConstructor
public class LicenseMgmtController {

    private final LicenseMgmtService licenseMgmtService;

    /**
     * 签发授权
     */
    @PostMapping("/issue")
    @OpsLog(action = "ISSUE_LICENSE", targetType = "AUTH_CODE")
    public R<CreateLicenseResult> issue(@Valid @RequestBody CreateLicenseRequest req) {
        return R.ok(licenseMgmtService.issueLicense(req));
    }

    /**
     * 吊销授权
     */
    @PostMapping("/revoke/{authCode}")
    @OpsLog(action = "REVOKE_LICENSE", targetType = "AUTH_CODE")
    public R<Void> revoke(@PathVariable String authCode,
                          @RequestParam(defaultValue = "运营吊销") String reason) {
        licenseMgmtService.revokeLicense(authCode, reason);
        return R.ok();
    }

    /**
     * 恢复授权
     */
    @PostMapping("/restore/{authCode}")
    @OpsLog(action = "RESTORE_LICENSE", targetType = "AUTH_CODE")
    public R<RestoreResult> restore(@PathVariable String authCode,
                                    @RequestParam(defaultValue = "运营恢复") String reason) {
        return R.ok(licenseMgmtService.restoreLicense(authCode, reason));
    }

    /**
     * 换机（使当前指纹失效）
     */
    @PostMapping("/rebind/{authCode}")
    @OpsLog(action = "REBIND", targetType = "AUTH_CODE")
    public R<Void> rebind(@PathVariable String authCode) {
        licenseMgmtService.invalidateFingerprint(authCode);
        return R.ok();
    }

    /**
     * 取消换机
     */
    @PostMapping("/cancel-rebind/{authCode}")
    @OpsLog(action = "CANCEL_REBINDING", targetType = "AUTH_CODE")
    public R<Void> cancelRebind(@PathVariable String authCode,
                                @RequestParam(defaultValue = "客户放弃换机") String reason) {
        licenseMgmtService.cancelRebinding(authCode, reason);
        return R.ok();
    }

    /**
     * 从黑名单移除
     */
    @PostMapping("/blacklist/remove/{authCode}")
    @OpsLog(action = "BLACKLIST_REMOVE", targetType = "BLACKLIST")
    public R<Void> removeFromBlacklist(@PathVariable String authCode) {
        licenseMgmtService.removeFromBlacklist(authCode);
        return R.ok();
    }

    /**
     * 分页查询签发流水
     */
    @GetMapping("/issue-records")
    public R<IPage<AuthIssueRecord>> pageIssueRecords(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String customerNo) {
        return R.ok(licenseMgmtService.pageIssueRecords(pageNum, pageSize, customerNo));
    }
}
