package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.BackupRecord;
import com.panjia.console.customer.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * 上传备份文件还原（pg_restore 覆盖当前数据库，危险操作）
     * <p>
     * 还原场景是换机器/数据迁移：备份文件在别的机器上，上传后还原。
     */
    @PostMapping("/restore")
    @OpsLog(action = "RESTORE_BACKUP", targetType = "BACKUP")
    public R<Void> restore(@RequestParam("file") MultipartFile file) {
        try {
            backupService.restoreFromUpload(file);
            return R.ok();
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 下载备份文件（pg_dump 自定义格式 .dump）
     */
    @GetMapping("/{id}/download")
    @OpsLog(action = "DOWNLOAD_BACKUP", targetType = "BACKUP")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Path path;
        try {
            path = backupService.getFileForDownload(id);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(null);
        }
        String fileName = path.getFileName().toString();
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .body(new FileSystemResource(path));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
