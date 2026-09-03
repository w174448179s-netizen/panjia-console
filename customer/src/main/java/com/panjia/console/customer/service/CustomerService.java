package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.Customer;
import com.panjia.console.customer.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户档案服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerMapper customerMapper;

    /**
     * 根据 ID 查询客户
     *
     * @param id 客户 ID
     * @return 客户信息
     */
    public Customer getById(Long id) {
        return customerMapper.selectById(id);
    }

    /**
     * 分页查询客户列表
     *
     * @param pageNum  页码
     * @param pageSize 每页大小
     * @param keyword  关键词（客户编号/名称模糊匹配）
     * @return 分页结果
     */
    public IPage<Customer> listCustomers(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Customer::getCustomerNo, keyword)
                    .or().like(Customer::getCustomerName, keyword));
        }
        wrapper.orderByDesc(Customer::getCreatedAt);
        return customerMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 查询所有客户列表
     *
     * @return 客户列表
     */
    public List<Customer> listAll() {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Customer::getCreatedAt);
        return customerMapper.selectList(wrapper);
    }

    /**
     * 根据客户编号查询
     *
     * @param customerNo 客户编号
     * @return 客户信息
     */
    public Customer getByCustomerNo(String customerNo) {
        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Customer::getCustomerNo, customerNo);
        return customerMapper.selectOne(wrapper);
    }

    /**
     * 创建客户
     *
     * @param customer 客户信息
     * @return 创建后的客户
     */
    public Customer createCustomer(Customer customer) {
        OffsetDateTime now = OffsetDateTime.now();
        customer.setId(null);
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);
        customerMapper.insert(customer);
        log.info("Created customer: customerNo={}, customerName={}",
                customer.getCustomerNo(), customer.getCustomerName());
        return customer;
    }

    /**
     * 更新客户信息
     *
     * @param customer 客户信息
     * @return 更新后的客户
     */
    public Customer updateCustomer(Customer customer) {
        customer.setUpdatedAt(OffsetDateTime.now());
        customerMapper.updateById(customer);
        log.info("Updated customer: id={}, customerNo={}", customer.getId(), customer.getCustomerNo());
        return customer;
    }

    /**
     * 删除客户
     *
     * @param id 客户 ID
     */
    public void deleteCustomer(Long id) {
        customerMapper.deleteById(id);
        log.info("Deleted customer: id={}", id);
    }
}
