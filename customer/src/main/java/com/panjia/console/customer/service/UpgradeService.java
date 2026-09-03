package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.ProductVersion;
import com.panjia.console.customer.domain.UpgradeRecord;
import com.panjia.console.customer.mapper.ProductVersionMapper;
import com.panjia.console.customer.mapper.UpgradeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 升级服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpgradeService {

    private final UpgradeRecordMapper upgradeRecordMapper;
    private final ProductVersionMapper productVersionMapper;

    /**
     * 根据 ID 查询升级记录
     *
     * @param id 升级 ID
     * @return 升级记录
     */
    public UpgradeRecord getById(Long id) {
        return upgradeRecordMapper.selectById(id);
    }

    /**
     * 分页查询升级记录
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param status   状态（可选）
     * @return 分页结果
     */
    public IPage<UpgradeRecord> pageRecords(int pageNum, int pageSize, String status) {
        LambdaQueryWrapper<UpgradeRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(UpgradeRecord::getStatus, status);
        }
        wrapper.orderByDesc(UpgradeRecord::getCreatedAt);
        return upgradeRecordMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 创建升级任务
     *
     * @param fromVersion 源版本
     * @param toVersion   目标版本
     * @param operator    操作人
     * @param backupId    关联备份 ID
     * @return 升级记录
     */
    public UpgradeRecord createUpgrade(String fromVersion, String toVersion, String operator, Long backupId) {
        UpgradeRecord record = new UpgradeRecord();
        record.setFromVersion(fromVersion);
        record.setToVersion(toVersion);
        record.setStatus("PENDING");
        record.setOperator(operator);
        record.setBackupId(backupId);
        record.setStartedAt(OffsetDateTime.now());
        upgradeRecordMapper.insert(record);

        log.info("Created upgrade task: id={}, from={}, to={}", record.getId(), fromVersion, toVersion);
        return record;
    }

    /**
     * 更新升级状态
     *
     * @param id       升级 ID
     * @param status   状态
     * @param errorMsg 错误信息（失败时）
     */
    public void updateUpgradeStatus(Long id, String status, String errorMsg) {
        UpgradeRecord record = upgradeRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("Upgrade record not found: id=" + id);
        }
        record.setStatus(status);
        record.setErrorMsg(errorMsg);
        if ("SUCCESS".equals(status) || "FAILED".equals(status) || "ROLLBACK_SUCCESS".equals(status)) {
            record.setFinishedAt(OffsetDateTime.now());
        }
        upgradeRecordMapper.updateById(record);
        log.info("Updated upgrade status: id={}, status={}", id, status);
    }

    /**
     * 查询产品版本列表
     *
     * @return 产品版本列表（按发布时间倒序）
     */
    public List<ProductVersion> listProductVersions() {
        LambdaQueryWrapper<ProductVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(ProductVersion::getReleasedAt);
        return productVersionMapper.selectList(wrapper);
    }

    /**
     * 新增产品版本
     *
     * @param version 产品版本
     * @return 新增后的版本
     */
    public ProductVersion addProductVersion(ProductVersion version) {
        version.setId(null);
        version.setCreatedAt(OffsetDateTime.now());
        productVersionMapper.insert(version);
        log.info("Added product version: id={}, version={}", version.getId(), version.getVersion());
        return version;
    }

    /**
     * 删除产品版本
     *
     * @param id 版本 ID
     */
    public void deleteProductVersion(Long id) {
        productVersionMapper.deleteById(id);
        log.info("Deleted product version: id={}", id);
    }
}
