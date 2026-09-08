package com.panjia.console.license.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 ConcurrentHashMap 幂等存储（单实例默认实现）
 * <p>
 * ★ H3 安全修复：从 ActivateService 中提取的独立组件。
 * 单实例部署足够；集群部署时切换为分布式实现（如 Redis）。
 * <p>
 * ★ S-4 防御：最大容量 10_000，超限时主动清扫过期项，
 * 仍超限则放弃缓存新结果（幂等降级为"每次走完整流程"，功能无损）。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "panjia.license",
        name = "idempotent-cache-type",
        havingValue = "local",
        matchIfMissing = true
)
public class LocalIdempotentStore implements IdempotentStore {

    /** 幂等缓存最大容量，防内存泄漏 */
    private static final int MAX_CACHE_ENTRIES = 10_000;

    /** (authCode|requestId) → {value, expireAt} */
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            cache.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            sweepExpired();
        }
        if (cache.size() < MAX_CACHE_ENTRIES) {
            cache.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
        } else {
            log.warn("[LocalIdempotentStore] 缓存已达上限 {}，降级不缓存该结果", MAX_CACHE_ENTRIES);
        }
    }

    @Override
    public void remove(String key) {
        cache.remove(key);
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public int sweepExpired() {
        long now = System.currentTimeMillis();
        int before = cache.size();
        cache.entrySet().removeIf(e -> now > e.getValue().expireAt);
        int swept = before - cache.size();
        if (swept > 0) {
            log.info("[LocalIdempotentStore] 清扫 {} 个过期项，剩余 {}", swept, cache.size());
        }
        return swept;
    }

    /** 缓存条目 */
    private static final class Entry {
        final String value;
        final long expireAt;

        Entry(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
