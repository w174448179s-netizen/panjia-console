package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.BackupRecord;
import com.panjia.console.customer.mapper.BackupRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 备份服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final BackupRecordMapper backupRecordMapper;

    /**
     * 根据 ID 查询备份记录
     *
     * @param id 备份 ID
     * @return 备份记录
     */
    public BackupRecord getById(Long id) {
        return backupRecordMapper.selectById(id);
    }

    /**
     * 分页查询备份记录
     *
     * @param pageNum    页码
     * @param pageSize   每页大小
     * @param backupType 备份类型（可选）
     * @param status     状态（可选）
     * @return 分页结果
     */
    public IPage<BackupRecord> pageRecords(int pageNum, int pageSize, String backupType, String status) {
        LambdaQueryWrapper<BackupRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(backupType)) {
            wrapper.eq(BackupRecord::getBackupType, backupType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(BackupRecord::getStatus, status);
        }
        wrapper.orderByDesc(BackupRecord::getCreatedAt);
        return backupRecordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 创建备份任务
     *
     * @param backupType 备份类型
     * @param operator   操作人
     * @param remark     备注
     * @return 备份记录
     */
    public BackupRecord createBackup(String backupType, String operator, String remark) {
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = "backup_" + backupType.toLowerCase() + "_" + timestamp + ".tar.gz";

        BackupRecord record = new BackupRecord();
        record.setFileName(fileName);
        record.setFilePath("/backups/" + fileName);
        record.setBackupType(backupType);
        record.setStatus("PENDING");
        record.setOperator(operator);
        record.setRemark(remark);
        record.setStartedAt(OffsetDateTime.now());
        backupRecordMapper.insert(record);

        log.info("Created backup task: id={}, type={}, fileName={}", record.getId(), backupType, fileName);
        return record;
    }

    /**
     * 更新备份状态
     *
     * @param id       备份 ID
     * @param status   状态
     * @param errorMsg 错误信息（失败时）
     * @param fileSize 文件大小
     * @param sha256   SHA256 校验
     */
    public void updateBackupStatus(Long id, String status, String errorMsg, Long fileSize, String sha256) {
        BackupRecord record = backupRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("Backup record not found: id=" + id);
        }
        record.setStatus(status);
        record.setErrorMsg(errorMsg);
        record.setFileSize(fileSize);
        record.setSha256(sha256);
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            record.setFinishedAt(OffsetDateTime.now());
        }
        backupRecordMapper.updateById(record);
        log.info("Updated backup status: id={}, status={}", id, status);
    }

    /**
     * 删除备份记录
     *
     * @param id 备份 ID
     */
    public void deleteRecord(Long id) {
        backupRecordMapper.deleteById(id);
        log.info("Deleted backup record: id={}", id);
    }
}
