package dev.eab.platform;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 时间统一写成 2026-10-15T08:00:00.000Z（UTC、毫秒精度），与前端和 TS 版的 toISOString 一致，可以按字符串比较 */
public final class IsoTime {

    private IsoTime() {}

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    public static String now(Clock clock) {
        return format(clock.instant());
    }
}
