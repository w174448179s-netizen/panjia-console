package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.Customer;
import com.panjia.console.customer.domain.CustomerRuntime;
import com.panjia.console.customer.mapper.CustomerMapper;
import com.panjia.console.customer.mapper.CustomerRuntimeMapper;
import com.panjia.console.license.api.LicenseEngine;
import com.panjia.console.license.api.dto.HeartbeatSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时监控服务
 * <p>
 * 负责客户运行时快照的刷新、查询和看板数据聚合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeMonitorService {

    private final LicenseEngine licenseEngine;
    private final CustomerMapper customerMapper;
    private final CustomerRuntimeMapper customerRuntimeMapper;

    /**
     * 分页查询运行时快照列表
     *
     * @param pageNum      页码
     * @param pageSize     每页大小
     * @param onlineStatus 在线状态（可选）
     * @return 分页结果
     */
    public IPage<CustomerRuntime> pageRuntimes(int pageNum, int pageSize, String onlineStatus) {
        LambdaQueryWrapper<CustomerRuntime> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(onlineStatus)) {
            wrapper.eq(CustomerRuntime::getOnlineStatus, onlineStatus);
        }
        wrapper.orderByDesc(CustomerRuntime::getUpdatedAt);
        return customerRuntimeMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 根据客户编号获取运行时快照
     *
     * @param customerNo 客户编号
     * @return 运行时快照
     */
    public CustomerRuntime getByCustomerNo(String customerNo) {
        LambdaQueryWrapper<CustomerRuntime> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerRuntime::getCustomerNo, customerNo);
        return customerRuntimeMapper.selectOne(wrapper);
    }

    /**
     * 从 license 模块刷新指定客户的运行时快照
     *
     * @param customerNo 客户编号
     */
    public void refreshRuntime(String customerNo) {
        HeartbeatSnapshot snapshot = licenseEngine.getHeartbeatSnapshot(customerNo);
        if (snapshot == null) {
            log.debug("No heartbeat snapshot for customer: {}", customerNo);
            return;
        }
        upsertRuntime(snapshot);
    }

    /**
     * 刷新所有客户的运行时快照（定时任务调用）
     *
     * @return 刷新条数
     */
    public int refreshAllRuntimes() {
        List<Customer> customers = customerMapper.selectList(null);
        int count = 0;
        for (Customer customer : customers) {
            try {
                refreshRuntime(customer.getCustomerNo());
                count++;
            } catch (Exception e) {
                log.warn("Failed to refresh runtime for customer: {}", customer.getCustomerNo(), e);
            }
        }
        log.info("Refreshed all runtimes: count={}", count);
        return count;
    }

    /**
     * 获取看板统计数据
     *
     * @return 统计数据 Map
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        long totalCustomers = customerMapper.selectCount(null);

        LambdaQueryWrapper<CustomerRuntime> onlineWrapper = new LambdaQueryWrapper<>();
        onlineWrapper.eq(CustomerRuntime::getOnlineStatus, "ONLINE");
        long onlineCount = customerRuntimeMapper.selectCount(onlineWrapper);

        LambdaQueryWrapper<CustomerRuntime> offlineWrapper = new LambdaQueryWrapper<>();
        offlineWrapper.eq(CustomerRuntime::getOnlineStatus, "OFFLINE");
        long offlineCount = customerRuntimeMapper.selectCount(offlineWrapper);

        LambdaQueryWrapper<CustomerRuntime> restrictWrapper = new LambdaQueryWrapper<>();
        restrictWrapper.eq(CustomerRuntime::getClientMode, "RESTRICT");
        long restrictCount = customerRuntimeMapper.selectCount(restrictWrapper);

        stats.put("totalCustomers", totalCustomers);
        stats.put("onlineCount", onlineCount);
        stats.put("offlineCount", offlineCount);
        stats.put("restrictCount", restrictCount);
        stats.put("updatedAt", OffsetDateTime.now());

        return stats;
    }

    /**
     * 心跳上报（客户端心跳回调，更新运行时快照）
     *
     * @param customerNo 客户编号
     * @param instanceId 实例 ID
     */
    public void onHeartbeat(String customerNo, String instanceId) {
        CustomerRuntime runtime = getByCustomerNo(customerNo);
        if (runtime == null) {
            runtime = new CustomerRuntime();
            runtime.setCustomerNo(customerNo);
            runtime.setInstanceId(instanceId);
            runtime.setOnlineStatus("ONLINE");
            runtime.setLastHeartbeatAt(OffsetDateTime.now());
            runtime.setLastCheckAt(OffsetDateTime.now());
            runtime.setVerified(true);
            runtime.setUpdatedAt(OffsetDateTime.now());
            customerRuntimeMapper.insert(runtime);
        } else {
            runtime.setInstanceId(instanceId);
            runtime.setOnlineStatus("ONLINE");
            runtime.setLastHeartbeatAt(OffsetDateTime.now());
            runtime.setUpdatedAt(OffsetDateTime.now());
            customerRuntimeMapper.updateById(runtime);
        }
    }

    /**
     * 根据心跳快照 upsert 运行时记录
     */
    private void upsertRuntime(HeartbeatSnapshot snapshot) {
        LambdaQueryWrapper<CustomerRuntime> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerRuntime::getCustomerNo, snapshot.getCustomerNo());
        CustomerRuntime runtime = customerRuntimeMapper.selectOne(wrapper);

        if (runtime == null) {
            runtime = new CustomerRuntime();
            runtime.setCustomerNo(snapshot.getCustomerNo());
            runtime.setInstanceId(snapshot.getInstanceId());
            runtime.setLastHeartbeatAt(snapshot.getLastHeartbeatAt());
            runtime.setOnlineStatus(snapshot.getOnlineStatus());
            runtime.setCurrentStores(snapshot.getCurrentStores());
            runtime.setCurrentUsers(snapshot.getCurrentUsers());
            runtime.setClientMode(snapshot.getClientMode());
            runtime.setLastCheckAt(OffsetDateTime.now());
            runtime.setVerified(true);
            runtime.setUpdatedAt(OffsetDateTime.now());
            customerRuntimeMapper.insert(runtime);
        } else {
            runtime.setInstanceId(snapshot.getInstanceId());
            runtime.setLastHeartbeatAt(snapshot.getLastHeartbeatAt());
            runtime.setOnlineStatus(snapshot.getOnlineStatus());
            runtime.setCurrentStores(snapshot.getCurrentStores());
            runtime.setCurrentUsers(snapshot.getCurrentUsers());
            runtime.setClientMode(snapshot.getClientMode());
            runtime.setLastCheckAt(OffsetDateTime.now());
            runtime.setUpdatedAt(OffsetDateTime.now());
            customerRuntimeMapper.updateById(runtime);
        }
    }
}
