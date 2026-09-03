package com.panjia.console.common.enums;

/**
 * 客户端模式枚举
 * <p>
 * 服务端始终自行计算 clientMode，客户端只负责执行。
 * 严禁信任客户端传入的 clientMode。
 */
public enum ClientMode {

    /** 正常模式 */
    NORMAL,

    /** 受限模式 */
    RESTRICT
}
