package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.AuthIssueRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 签发流水 Mapper
 */
@Mapper
public interface AuthIssueRecordMapper extends BaseMapper<AuthIssueRecord> {

    /**
     * 分页查询签发流水（联表 auth.t_auth_code 取当前状态）
     * <p>
     * 投影表不存状态，状态以 auth.t_auth_code 为权威源，实时联表获取。
     *
     * @param page       分页参数
     * @param customerNo 客户编号（可选过滤）
     * @return 签发流水分页（含 status）
     */
    @Select("SELECT r.id, r.license_id, r.customer_no, r.auth_code, r.license_type, " +
            "r.version, r.max_stores, r.max_users, r.capabilities, " +
            "r.start_date, r.end_date, r.maintenance_end_date, " +
            "r.operator, r.issue_at, r.created_at, " +
            "COALESCE(ac.status, 'UNKNOWN') AS status " +
            "FROM customer.pj_auth_issue_record r " +
            "LEFT JOIN auth.t_auth_code ac ON r.auth_code = ac.auth_code " +
            "WHERE (#{customerNo} IS NULL OR #{customerNo} = '' OR r.customer_no = #{customerNo}) " +
            "ORDER BY r.issue_at DESC")
    IPage<AuthIssueRecord> pageIssueRecordsWithStatus(Page<AuthIssueRecord> page,
                                                       @Param("customerNo") String customerNo);
}
