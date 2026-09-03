package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警同步结果 DTO
 * <p>
 * customer 模块通过 syncAlerts(afterId, limit) 以 ID cursor 拉取告警。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertSyncResult {

    /** 告警记录列表 */
    private List<AlertRecordDTO> records;

    /** 本批最后一条记录的 ID（下一次同步的 afterId） */
    private Long lastId;

    /** 是否还有更多 */
    private boolean hasMore;
}
