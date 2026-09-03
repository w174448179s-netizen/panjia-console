package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.ProductVersion;
import com.panjia.console.customer.domain.UpgradeRecord;
import com.panjia.console.customer.service.UpgradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 升级管理 Controller
 * <p>
 * 管理面接口，负责系统升级任务和产品版本管理。
 */
@RestController
@RequestMapping("/api/v1/upgrades")
@RequiredArgsConstructor
public class UpgradeController {

    private final UpgradeService upgradeService;

    /**
     * 分页查询升级记录
     */
    @GetMapping
    public R<IPage<UpgradeRecord>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        return R.ok(upgradeService.pageRecords(pageNum, pageSize, status));
    }

    /**
     * 根据 ID 查询升级详情
     */
    @GetMapping("/{id}")
    public R<UpgradeRecord> getById(@PathVariable Long id) {
        return R.ok(upgradeService.getById(id));
    }

    /**
     * 创建升级任务
     */
    @PostMapping
    @OpsLog(action = "CREATE_UPGRADE", targetType = "UPGRADE")
    public R<UpgradeRecord> create(
            @RequestParam(required = false) String fromVersion,
            @RequestParam String toVersion,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) Long backupId) {
        return R.ok(upgradeService.createUpgrade(fromVersion, toVersion, operator, backupId));
    }

    /**
     * 查询产品版本列表
     */
    @GetMapping("/versions")
    public R<List<ProductVersion>> listVersions() {
        return R.ok(upgradeService.listProductVersions());
    }

    /**
     * 新增产品版本
     */
    @PostMapping("/versions")
    @OpsLog(action = "ADD_PRODUCT_VERSION", targetType = "PRODUCT_VERSION")
    public R<ProductVersion> addVersion(@RequestBody ProductVersion version) {
        return R.ok(upgradeService.addProductVersion(version));
    }

    /**
     * 删除产品版本
     */
    @DeleteMapping("/versions/{id}")
    @OpsLog(action = "DELETE_PRODUCT_VERSION", targetType = "PRODUCT_VERSION")
    public R<Void> deleteVersion(@PathVariable Long id) {
        upgradeService.deleteProductVersion(id);
        return R.ok();
    }
}
