package com.panjia.console.license.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 授权服务 JWT / JKS 配置属性
 * <p>
 * ★ 安全注意（H5）：
 * <ul>
 *   <li>jksPassword / keyPassword 必须从环境变量注入，禁止明文写 application.yml</li>
 *   <li>jks 文件权限必须为 600，不打包进 JAR、不入 Git</li>
 *   <li>私钥永远不出进程，仅 JwtIssuer 加载</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "panjia.license")
public class JwtConfigProperties {

    /** JKS 文件路径 */
    private String jksPath;

    /** JKS 密码（从环境变量 PANJIA_JKS_PASSWORD 注入） */
    private String jksPassword;

    /** 密钥别名 */
    private String keyAlias;

    /** 密钥密码（从环境变量 PANJIA_KEY_PASSWORD 注入） */
    private String keyPassword;

    /**
     * ★ H2 安全修复：可信代理 CIDR 列表
     * <p>
     * 解析 X-Forwarded-For 时，从右向左跳过这些 CIDR 范围内的 IP，
     * 第一个非可信代理的 IP 即为真实客户端 IP。
     * <p>
     * 默认覆盖 Docker 三个私有网段 + 回环地址，覆盖典型部署：
     * 127.0.0.1/32（本机）、10.0.0.0/8、172.16.0.0/12、192.168.0.0/16
     * <p>
     * 生产环境可通过环境变量 PANJIA_TRUSTED_PROXIES 覆盖，
     * 如只信任 nginx 容器 IP：PANJIA_TRUSTED_PROXIES=172.18.0.3/32
     */
    private List<String> trustedProxyCidrs = List.of(
            "127.0.0.1/32",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16"
    );

    /**
     * JWT 有效期（天），默认 14 天。
     * <p>
     * sizing 依据（P0-A/P0-B 修复后的口径）：
     * <ul>
     *   <li>客户端离线宽限期 offlineGraceMs 默认 7 天；</li>
     *   <li>心跳间隔 24h，续签阈值 {@link #tokenRenewThresholdDays} 默认 7 天，
     *       即 token 剩余寿命不足 7 天时，下一次心跳自动重签；</li>
     *   <li>14 = 7（续签阈值）+ 7（一次续签周期失败的安全余量）。
     *       即使某次续签失败，token 仍剩 7 天 = 完整离线宽限期，
     *       token 永远不会先于离线宽限锁死。</li>
     * </ul>
     */
    private int jwtExpireDays = 14;

    /**
     * 心跳续签阈值（天），默认 7 天。
     * <p>
     * 心跳处理时若当前 JWT 剩余寿命小于此值，服务端基于当前授权重签新 JWT
     * 并通过心跳响应的 token 字段下发（客户端 LicenseServiceImpl.renewToken 接收）。
     * <p>
     * 必须不小于客户端离线宽限期（默认 7 天），否则"断网瞬间 token 剩余寿命
     * 小于宽限期"，token 会先于宽限期过期导致提前锁死。
     */
    private int tokenRenewThresholdDays = 7;

    /** 离线宽限天数，默认 7 天 */
    private int offlineExpireDays = 7;

    /** 心跳归档保留天数，默认 180 天 */
    private int heartbeatRetentionDays = 180;

    /** 多实例检测窗口（小时），默认 24 小时 */
    private int multiInstanceWindowHours = 24;

    /** 多实例确认次数阈值，默认 3 次 */
    private int multiInstanceConfirmCount = 3;

    /** IP 多实例检测窗口（分钟），默认 60 分钟 */
    private int ipMismatchWindowMinutes = 60;

    /** IP 多实例确认次数阈值，默认 2 次 */
    private int ipMismatchConfirmCount = 2;

    /**
     * JWT 时钟偏差容忍（秒），默认 60 秒。
     * <p>
     * 必须与客户端 {@code panjia.license.clockSkewSeconds} 保持一致，
     * 否则双端时钟漂移时 token 会被误判过期导致首次激活 401。
     */
    private int clockSkewSeconds = 60;

    /**
     * ★ H3 安全修复：幂等缓存类型
     * <p>
     * <ul>
     *   <li>local — 进程内 ConcurrentHashMap（默认，单实例部署）</li>
     *   <li>redis — 分布式缓存（集群部署，需引入 Redis 依赖）</li>
     * </ul>
     * 单实例部署用 local 足够；集群部署必须切换为 redis，
     * 否则同一 requestId 的重试请求落在不同实例上会绕过幂等短路。
     */
    private String idempotentCacheType = "local";
}
