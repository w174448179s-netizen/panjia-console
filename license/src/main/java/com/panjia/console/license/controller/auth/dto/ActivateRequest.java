package com.panjia.console.license.controller.auth.dto;

import com.panjia.console.common.util.TextSanitizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 激活请求 DTO
 * <p>
 * ★ requestId 设计为可选幂等键：
 * <ul>
 *   <li>客户端必须生成 UUID v4 并附带（推荐）</li>
 *   <li>服务端在 (authCode, requestId) 上做幂等：同一 requestId 重复提交返回同一结果</li>
 *   <li>未携带 requestId 时按"无幂等"语义处理，每次都重新走完整流程</li>
 * </ul>
 * 目的：解决客户网络抖动重试导致的 409 CONCURRENT_ACTIVATE 死循环。
 * <p>
 * ★ S-6 修复：requestId/company/instanceId 在 setter 层消毒控制字符，防 CRLF 日志注入。
 */
@Data
public class ActivateRequest {

    /** 授权码 */
    @NotBlank(message = "授权码不能为空")
    private String authCode;

    /** 指纹原文 */
    @NotBlank(message = "指纹不能为空")
    private String fingerprint;

    /** 产品版本 */
    private String productVersion;

    /** 公司名称（可选） */
    private String company;

    /** 实例 ID（可选） */
    private String instanceId;

    /** 请求幂等键（可选，UUID v4 格式，长度 8~64）。同一 requestId 重试返回同一结果。 */
    @Size(min = 8, max = 64, message = "requestId 长度必须在 8~64 之间")
    private String requestId;

    /** S-6：消毒控制字符，防日志注入 */
    public void setRequestId(String requestId) {
        this.requestId = TextSanitizer.stripControlChars(requestId);
    }

    public void setCompany(String company) {
        this.company = TextSanitizer.stripControlChars(company);
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = TextSanitizer.stripControlChars(instanceId);
    }
}
