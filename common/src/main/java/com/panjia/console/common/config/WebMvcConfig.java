package com.panjia.console.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * <p>
 * 管理面和鉴权面共用一个 Spring Boot 进程，
 * CORS 策略按路径区分（管理面内网，鉴权面公网）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 鉴权面（公网）：允许所有来源（客户端为桌面应用，不涉及浏览器 CORS）
        registry.addMapping("/api/auth/**")
                .allowedOriginPatterns("*")
                .allowedMethods("POST", "GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);

        // 管理面（内网/VPN）：允许所有来源（部署在内网，由 Nginx 做访问控制）
        registry.addMapping("/api/v1/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
