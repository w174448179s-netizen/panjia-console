package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.BackupRecord;
import com.panjia.console.customer.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 备份管理 Controller
 * <p>
 * 管理面接口，负责数据备份的创建、查询和管理。
 */
@RestController
@RequestMapping("/api/v1/backups")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    /**
     * 分页查询备份记录
     */
    @GetMapping
    public R<IPage<BackupRecord>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String backupType,
            @RequestParam(required = false) String status) {
        return R.ok(backupService.pageRecords(pageNum, pageSize, backupType, status));
    }

    /**
     * 根据 ID 查询备份详情
     */
    @GetMapping("/{id}")
    public R<BackupRecord> getById(@PathVariable Long id) {
        return R.ok(backupService.getById(id));
    }

    /**
     * 创建备份任务
     */
    @PostMapping
    @OpsLog(action = "CREATE_BACKUP", targetType = "BACKUP")
    public R<BackupRecord> create(
            @RequestParam(defaultValue = "FULL") String backupType,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String remark) {
        return R.ok(backupService.createBackup(backupType, operator, remark));
    }

    /**
     * 删除备份记录
     */
    @DeleteMapping("/{id}")
    @OpsLog(action = "DELETE_BACKUP", targetType = "BACKUP")
    public R<Void> delete(@PathVariable Long id) {
        backupService.deleteRecord(id);
        return R.ok();
    }
}
