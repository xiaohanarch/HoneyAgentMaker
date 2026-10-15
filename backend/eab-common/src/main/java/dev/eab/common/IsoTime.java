package dev.eab.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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

    /** 日期部分 YYYY-MM-DD（UTC） */
    public static String date(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    /** 两个 YYYY-MM-DD 之间的天数 */
    public static long daysBetween(String from, String to) {
        return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
    }
}
