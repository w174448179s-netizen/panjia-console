package com.panjia.console.license.controller.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * check 响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckResponse {

    /** 客户端模式 */
    private String clientMode;

    /** 受限原因错误码（仅受限时有值） */
    private String code;

    /** 能力位列表 */
    private List<String> capabilities;

    /** 最大门店数 */
    private Integer maxStores;

    /** 最大用户数 */
    private Integer maxUsers;

    /** 授权到期日期 */
    private LocalDate endDate;
}
