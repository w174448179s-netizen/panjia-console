package com.panjia.console;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 盘家智管授权服务启动类
 * <p>
 * 单一 Spring Boot 应用，同进程承载两个模块：
 * <ul>
 *   <li>customer.* —— 运营操作台 + 看板（管理面，内网/VPN）</li>
 *   <li>license.* —— 授权引擎（鉴权面，公网 443）</li>
 * </ul>
 * 两模块通过 {@code LicenseEngine} 接口方法调用协作，不走 HTTP。
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan(value = "com.panjia.console", annotationClass = Mapper.class)
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
