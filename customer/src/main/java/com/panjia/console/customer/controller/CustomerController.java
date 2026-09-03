package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.Customer;
import com.panjia.console.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 客户档案 Controller
 * <p>
 * 管理面接口，负责客户档案的 CRUD 操作。
 */
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /**
     * 分页查询客户列表
     */
    @GetMapping
    public R<IPage<Customer>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return R.ok(customerService.listCustomers(pageNum, pageSize, keyword));
    }

    /**
     * 根据 ID 查询客户详情
     */
    @GetMapping("/{id}")
    public R<Customer> getById(@PathVariable Long id) {
        return R.ok(customerService.getById(id));
    }

    /**
     * 根据客户编号查询
     */
    @GetMapping("/no/{customerNo}")
    public R<Customer> getByCustomerNo(@PathVariable String customerNo) {
        return R.ok(customerService.getByCustomerNo(customerNo));
    }

    /**
     * 创建客户
     */
    @PostMapping
    @OpsLog(action = "CREATE_CUSTOMER", targetType = "CUSTOMER")
    public R<Customer> create(@RequestBody Customer customer) {
        return R.ok(customerService.createCustomer(customer));
    }

    /**
     * 更新客户信息
     */
    @PutMapping
    @OpsLog(action = "UPDATE_CUSTOMER", targetType = "CUSTOMER")
    public R<Customer> update(@RequestBody Customer customer) {
        return R.ok(customerService.updateCustomer(customer));
    }

    /**
     * 删除客户
     */
    @DeleteMapping("/{id}")
    @OpsLog(action = "DELETE_CUSTOMER", targetType = "CUSTOMER")
    public R<Void> delete(@PathVariable Long id) {
        customerService.deleteCustomer(id);
        return R.ok();
    }
}
