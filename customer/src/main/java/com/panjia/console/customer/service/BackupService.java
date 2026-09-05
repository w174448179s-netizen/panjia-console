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
import org.springframework.web.multipart.MultipartFile;

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
        record.setStatus("IN_PROGRESS");
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

    /**
     * 获取备份文件用于下载
     *
     * @throws IllegalStateException 记录不存在 / 未成功 / 文件已丢失
     */
    public Path getFileForDownload(Long id) {
        BackupRecord record = backupRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalStateException("备份记录不存在: " + id);
        }
        if (!"SUCCESS".equals(record.getStatus())) {
            throw new IllegalStateException("备份未成功完成，无法下载");
        }
        Path path = Paths.get(record.getFilePath());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("备份文件已丢失: " + record.getFileName());
        }
        return path;
    }

    /**
     * 还原备份（上传备份文件 → pg_restore 覆盖当前数据库，同步执行）
     * <p>
     * 还原场景是"换机器 / 数据迁移"：备份文件在别的机器上，通过页面上传后还原，
     * 不依赖本机备份目录里是否还有该文件。
     *
     * @throws IllegalStateException 文件为空 / 保存失败 / 还原失败
     */
    public void restoreFromUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("请选择要上传的备份文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !(originalName.endsWith(".dump") || originalName.endsWith(".backup"))) {
            throw new IllegalStateException("仅支持 pg_dump 自定义格式的备份文件（.dump）");
        }

        // 上传文件先落临时文件，再交给 pg_restore，完毕即删
        Path tempFile = null;
        try {
            Path dir = Paths.get(backupDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            tempFile = Files.createTempFile(dir, "restore_", ".dump");
            file.transferTo(tempFile);

            runRestore(tempFile);
            log.info("Restore from upload completed: originalName={}, size={}", originalName, file.getSize());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("还原执行失败: " + e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Failed to delete temp restore file: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 从已有备份记录还原（选择服务器上的备份文件 → pg_restore 覆盖当前数据库）
     *
     * @throws IllegalStateException 记录不存在 / 未成功 / 文件已丢失 / 还原失败
     */
    public void restoreFromRecord(Long id) {
        BackupRecord record = backupRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalStateException("备份记录不存在: " + id);
        }
        if (!"SUCCESS".equals(record.getStatus())) {
            throw new IllegalStateException("备份未成功完成，无法还原");
        }
        Path path = Paths.get(record.getFilePath());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("备份文件已丢失: " + record.getFileName());
        }
        try {
            runRestore(path);
            log.info("Restore from record completed: id={}, fileName={}", id, record.getFileName());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("还原执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 pg_restore（--clean --if-exists 覆盖式还原）
     */
    private void runRestore(Path path) throws Exception {
        // 从 JDBC URL 解析 host:port/dbname
        String host = "localhost";
        String port = "5432";
        String dbName = "postgres";
        String url = datasourceUrl.replace("jdbc:postgresql://", "");
        int slashIdx = url.indexOf('/');
        int qIdx = url.indexOf('?');
        String hostPort = url.substring(0, slashIdx);
        dbName = qIdx > 0 ? url.substring(slashIdx + 1, qIdx) : url.substring(slashIdx + 1);
        int colonIdx = hostPort.indexOf(':');
        if (colonIdx > 0) {
            host = hostPort.substring(0, colonIdx);
            port = hostPort.substring(colonIdx + 1);
        } else {
            host = hostPort;
        }

        // --clean --if-exists: 先 DROP 已有对象再重建（覆盖式还原）
        ProcessBuilder pb = new ProcessBuilder(
                "pg_restore",
                "-h", host,
                "-p", port,
                "-U", datasourceUsername,
                "--clean", "--if-exists",
                "-d", dbName,
                path.toString()
        );
        pb.environment().put("PGPASSWORD", datasourcePassword);
        pb.redirectErrorStream(true);

        log.warn("Restoring from file {} to {}:{}/{} (this will overwrite the database)", path, host, port, dbName);
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
            log.error("pg_restore failed: {}", out);
            throw new IllegalStateException("pg_restore failed (exit=" + exitCode + "): " + out);
        }
    }
}
