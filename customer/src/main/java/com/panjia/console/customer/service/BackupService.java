package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.BackupRecord;
import com.panjia.console.customer.mapper.BackupRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 备份服务
 * <p>
 * 使用 pg_dump 导出整个 postgres 数据库为自定义格式（.dump），
 * 支持还原（pg_restore）。备份文件存放在配置的备份目录中。
 */
@Slf4j
@Service
public class BackupService {

    private final BackupRecordMapper backupRecordMapper;

    @Value("${panjia.backup.dir:./backups}")
    private String backupDir;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    public BackupService(BackupRecordMapper backupRecordMapper) {
        this.backupRecordMapper = backupRecordMapper;
    }

    /**
     * 分页查询备份记录
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
     * 根据 ID 查询备份记录
     */
    public BackupRecord getById(Long id) {
        return backupRecordMapper.selectById(id);
    }

    /**
     * 创建备份（异步执行 pg_dump）
     */
    public BackupRecord createBackup(String backupType, String operator, String remark) {
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String fileName = "backup_" + (backupType == null ? "full" : backupType.toLowerCase()) + "_" + timestamp + ".dump";
        Path filePath = Paths.get(backupDir, fileName);

        BackupRecord record = new BackupRecord();
        record.setFileName(fileName);
        record.setFilePath(filePath.toString());
        record.setBackupType(backupType != null ? backupType : "FULL");
        record.setStatus("RUNNING");
        record.setOperator(operator);
        record.setRemark(remark);
        record.setStartedAt(OffsetDateTime.now());
        backupRecordMapper.insert(record);

        doBackupAsync(record.getId(), filePath);

        log.info("Backup started: id={}, fileName={}", record.getId(), fileName);
        return record;
    }

    /**
     * 异步执行 pg_dump
     */
    @Async
    public void doBackupAsync(Long recordId, Path filePath) {
        try {
            Path dir = filePath.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 从 JDBC URL 解析 host:port/dbname
            String host = "localhost";
            int port = 5432;
            String dbName = "postgres";
            String url = datasourceUrl.replace("jdbc:postgresql://", "");
            int slashIdx = url.indexOf('/');
            int qIdx = url.indexOf('?');
            String hostPort = url.substring(0, slashIdx);
            dbName = qIdx > 0 ? url.substring(slashIdx + 1, qIdx) : url.substring(slashIdx + 1);
            if (hostPort.contains(":")) {
                String[] parts = hostPort.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else {
                host = hostPort;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "pg_dump",
                    "-h", host,
                    "-p", String.valueOf(port),
                    "-U", datasourceUsername,
                    "-F", "c",        // 自定义格式（压缩）
                    "-f", filePath.toString(),
                    dbName
            );
            pb.environment().put("PGPASSWORD", datasourcePassword);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("pg_dump failed (exit=" + exitCode + "): " + out);
            }

            long fileSize = Files.size(filePath);
            String sha256 = computeSha256(filePath);

            updateStatus(recordId, "SUCCESS", null, fileSize, sha256, filePath.toString());
            log.info("Backup completed: id={}, size={}, sha256={}", recordId, fileSize, sha256);

        } catch (Exception e) {
            log.error("Backup failed: id={}", recordId, e);
            updateStatus(recordId, "FAILED", e.getMessage(), null, null, null);
        }
    }

    private void updateStatus(Long id, String status, String errorMsg, Long fileSize, String sha256, String filePath) {
        BackupRecord record = backupRecordMapper.selectById(id);
        if (record == null) return;
        record.setStatus(status);
        record.setErrorMsg(errorMsg);
        record.setFileSize(fileSize);
        record.setSha256(sha256);
        if (filePath != null) {
            record.setFilePath(filePath);
        }
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            record.setFinishedAt(OffsetDateTime.now());
        }
        backupRecordMapper.updateById(record);
    }

    private String computeSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 删除备份记录（同时删除物理文件）
     */
    public void deleteRecord(Long id) {
        BackupRecord record = backupRecordMapper.selectById(id);
        if (record != null && record.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(record.getFilePath()));
            } catch (Exception e) {
                log.warn("Failed to delete backup file: {}", record.getFilePath(), e);
            }
        }
        backupRecordMapper.deleteById(id);
        log.info("Deleted backup record: id={}", id);
    }
}
