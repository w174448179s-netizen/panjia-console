package com.panjia.console.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 系统时间统一工具类。
 * <p>
 * ★ 全系统时区基准：Asia/Shanghai（北京时间）。
 * <p>
 * 所有业务代码禁止直接调用 LocalDate.now() / OffsetDateTime.now() 等
 * 依赖系统默认时区的方法，必须通过本工具类获取，确保跨时区部署时行为一致。
 * <p>
 * 设计原则：
 * - 纯日期（LocalDate）：以北京时间的"今天"为准
 * - 带时区时间（OffsetDateTime）：以北京时间偏移 +08:00
 * - 瞬时时间（Instant）：绝对时间，不受时区影响，直接用 Instant.now() 即可
 */
public final class TimeUtils {

    /** 系统统一时区：北京时间 */
    public static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");

    private TimeUtils() {}

    /**
     * 当前日期（北京时间）。
     * 用于授权到期日判断、每日扫描任务等"日历日期"相关的业务逻辑。
     */
    public static LocalDate today() {
        return LocalDate.now(SYSTEM_ZONE);
    }

    /**
     * 当前时间（北京时间，带时区偏移）。
     * 用于记录创建时间、更新时间、心跳时间等。
     */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(SYSTEM_ZONE);
    }

    /**
     * 当前瞬时时间（绝对时间，无时区）。
     * 用于时间戳比较、JWT 过期判断等。
     * 直接用 Instant.now() 也可以，这里只是为了统一入口。
     */
    public static Instant instantNow() {
        return Instant.now();
    }
}
