package com.panjia.console.common.enums;

/**
 * 控制台授权码状态枚举。
 *
 * ★ P2-1 状态机语义说明（两端维度差异 + 桥接表）：
 *
 * 本枚举是"授权态"维度（持久状态：授权码生命周期），与客户端 LicenseStatusEnum
 * "运行态"维度（动态切换：心跳、过期、模式指令）语义不同，**不强行合并**。
 *
 * 语义桥接表：
 * | 本枚举（控制台/授权态）| 客户端 LicenseStatusEnum（运行态） | 触发条件                          |
 * |----------------------|----------------------------------|----------------------------------|
 * | ACTIVE               | NORMAL / OFFLINE_GRACE /         | 授权未到期。客户端再细分为多个     |
 * |                      | RESTRICTED / FINGERPRINT_MISMATCH | 运行态                              |
 * | ACTIVE               | NOT_ACTIVATED                    | 客户尚未首次激活                   |
 * | REBINDING            | NORMAL                           | 客户申请换机，多实例豁免期         |
 * | EXPIRED              | EXPIRED / OFFLINE_LOCK           | end_date 已过；token 过期         |
 * | REVOKED              | LOCKED                           | 黑名单命中 / 人工吊销              |
 *
 * 注意：两端状态机都是不可逆终态向"持久态"收敛的（运行态 → EXPIRED/LOCKED → 持久 EXPIRED/REVOKED），
 * 反向流动（恢复授权）必须走运营控制台"恢复"按钮，不存在自动从 EXPIRED/REVOKED 反弹。
 *
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
