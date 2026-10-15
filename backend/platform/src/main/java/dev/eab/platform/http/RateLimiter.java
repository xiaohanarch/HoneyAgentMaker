package dev.eab.platform.http;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按来源地址的固定窗口限流，用于登录与令牌端点（每个地址每分钟 30 次）。
 * 演示环境单实例运行，计数放在内存里即可；多实例部署时应换成网关层或共享存储的限流。
 */
public final class RateLimiter {

    private record Window(long start, int count) {}

    private final int max;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int max, long windowMillis, Clock clock) {
        this.max = max;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** 超出限额时抛 429 */
    public void check(String key) {
        long now = clock.millis();
        Window window = windows.compute(
                key,
                (k, w) -> w == null || now - w.start() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(w.start(), w.count() + 1));
        if (window.count() > max) throw new ApiException(429, "RATE_LIMITED", "请求过于频繁，请稍后再试");
        if (windows.size() > 10_000)
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= windowMillis);
    }
}
