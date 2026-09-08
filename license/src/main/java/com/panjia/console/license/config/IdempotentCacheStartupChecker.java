package com.panjia.console.license.config;

import com.panjia.console.license.security.JwtConfigProperties;
import com.panjia.console.license.service.IdempotentStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ★ H3 安全修复：幂等缓存启动检查器
 * <p>
 * 启动时检测当前幂等缓存类型，如果是 local（进程内），
 * 打印警告提示集群部署时需切换为分布式实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentCacheStartupChecker {

    private final JwtConfigProperties config;
    private final IdempotentStore idempotentStore;

    @PostConstruct
    public void check() {
        String cacheType = config.getIdempotentCacheType();
        String implClass = idempotentStore.getClass().getSimpleName();

        if ("local".equals(cacheType)) {
            log.warn("[IdempotentCache] 当前使用 {}（进程内缓存，单实例部署）", implClass);
            log.warn("[IdempotentCache] ★ 集群部署（多实例）时必须切换为分布式实现（如 Redis），");
            log.warn("[IdempotentCache]   否则同一 requestId 的重试请求落在不同实例上会绕过幂等短路。");
            log.warn("[IdempotentCache]   切换方式：实现 IdempotentStore 接口 + 配置 panjia.license.idempotent-cache-type=redis");
        } else {
            log.info("[IdempotentCache] 当前使用 {}（cache-type={}）", implClass, cacheType);
        }
    }
}
