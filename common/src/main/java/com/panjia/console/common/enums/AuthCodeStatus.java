package com.panjia.console.common.enums;

/**
 * 授权码状态枚举
 * <p>
 * 四值状态机（冻结，无临时态）：ACTIVE / REBINDING / EXPIRED / REVOKED
 */
public enum AuthCodeStatus {

    /** 生效中 */
    ACTIVE,

    /** 换机中（多实例检测豁免；不设超时自动回滚，运营手动取消） */
    REBINDING,

    /** 已到期（定时扫描维护此字段，实时判定以 end_date 为准） */
    EXPIRED,

    /** 已吊销（仅 restore 可恢复） */
    REVOKED
}
