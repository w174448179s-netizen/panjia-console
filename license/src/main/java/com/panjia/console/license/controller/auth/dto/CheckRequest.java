package com.panjia.console.license.controller.auth.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * check 请求 DTO
 */
@Data
public class CheckRequest {

    /** 产品版本 */
    private String productVersion;

    /** 当前门店数 */
    private Integer currentStores;

    /** 当前用户数 */
    private Integer currentUsers;
}
