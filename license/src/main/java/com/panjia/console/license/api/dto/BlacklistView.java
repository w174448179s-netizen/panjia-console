package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 黑名单视图 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlacklistView {

    /** 授权码 */
    private String authCode;

    /** 客户编号 */
    private String customerNo;

    /** 拉黑原因 */
    private String reason;

    /** 拉黑时间 */
    private OffsetDateTime createdAt;
}
