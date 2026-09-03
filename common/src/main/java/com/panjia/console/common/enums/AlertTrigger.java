package com.panjia.console.common.enums;

/**
 * 告警 trigger 枚举
 * <p>
 * 与客户端 V1.1 §5.4 一致，四值：
 * T1_AUTH_FAIL / T2_SERVER_REVOKED / T3_INTEGRITY / SERVER_DECISION
 */
public enum AlertTrigger {

    /** 鉴权失败（含多实例检测） */
    T1_AUTH_FAIL,

    /** 服务端吊销/受限指令 */
    T2_SERVER_REVOKED,

    /** 完整性校验异常 */
    T3_INTEGRITY,

    /** 服务端运营决策（恢复/换机/取消换机等状态变更事件） */
    SERVER_DECISION
}
