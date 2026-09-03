package com.panjia.console.common.enums;

/**
 * 黑名单 reason 枚举
 */
public enum BlacklistReason {

    /** 吊销（不可通过 removeFromBlacklist 移除，必须 restore） */
    REVOKE,

    /** 手动加入（可移除） */
    MANUAL,

    /** 多实例检测触发（可移除） */
    MULTI_INSTANCE
}
