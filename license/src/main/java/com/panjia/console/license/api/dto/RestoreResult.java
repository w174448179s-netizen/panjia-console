package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 恢复授权结果 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestoreResult {

    /** 新的授权内容版本（旧版本 + 1） */
    private Integer licenseVersion;

    /** 恢复时间 */
    private OffsetDateTime restoredAt;
}
