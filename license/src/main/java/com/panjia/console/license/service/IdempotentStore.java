package com.panjia.console.license.service;

import java.time.Duration;

/**
 * 激活幂等存储接口
 * <p>
 * ★ H3 安全修复：将进程内 ConcurrentHashMap 提取为接口，支持集群部署时切换为分布式实现。
 * <p>
 * 仅缓存"成功"结果，失败路径不入缓存——保证参数修正后重试能拿到正确结果。
 * <p>
 * 实现类：
 * <ul>
 *   <li>{@link LocalIdempotentStore} — 进程内 ConcurrentHashMap（单实例默认）</li>
 *   <li>未来可添加 RedisIdempotentStore — 分布式实现（集群部署）</li>
 * </ul>
 */
public interface IdempotentStore {

    /**
     * 尝试获取缓存的幂等结果
     *
     * @param key 幂等键（authCode|requestId）
     * @return 缓存结果（序列化为 JSON 字符串），不存在或已过期返回 null
     */
    String get(String key);

    /**
     * 写入幂等缓存
     *
     * @param key     幂等键
     * @param value   序列化后的结果（JSON 字符串）
     * @param ttl     过期时间
     */
    void put(String key, String value, Duration ttl);

    /**
     * 移除指定缓存项
     *
     * @param key 幂等键
     */
    void remove(String key);

    /**
     * 当前缓存条目数（用于监控和容量判断）
     *
     * @return 缓存条目数
     */
    int size();

    /**
     * 清扫过期项（低频主动调用，摊销到请求里）
     *
     * @return 清扫的条目数
     */
    int sweepExpired();
}
