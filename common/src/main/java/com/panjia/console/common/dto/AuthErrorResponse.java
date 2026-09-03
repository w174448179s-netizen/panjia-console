package com.panjia.console.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 鉴权面统一错误响应
 * <p>
 * 遵循 §5.7 冻结规则：授权判定结果永远走 200 + clientMode；
 * 只有"请求本身非法"才用 4xx。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthErrorResponse {

    /** 错误码 */
    private String code;

    /** 错误消息（仅用于日志排查，客户端不应展示给用户） */
    private String message;

    /** 客户端模式（仅在 200 + 受限时返回） */
    private String clientMode;
}
