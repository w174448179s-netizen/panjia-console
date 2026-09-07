package com.panjia.console.license.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** JWT 有效期（天），默认 7 天 */
    private int jwtExpireDays = 7;

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
}
