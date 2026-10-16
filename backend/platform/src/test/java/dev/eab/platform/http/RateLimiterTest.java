package dev.eab.platform.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 固定窗口限流的单元测试：超限抛 429，窗口滚动后重新计数，不同来源地址互不影响 */
class RateLimiterTest {

    /** 可手动拨动的时钟，用来模拟固定窗口的时间流逝 */
    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000);

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(long ms) {
            millis.addAndGet(ms);
        }
    }

    @Test
    @DisplayName("同一来源地址在一分钟内超过配额时抛 429，其他地址不受影响")
    void exceedsQuota() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(2, 60_000, clock);
        assertThatCode(() -> limiter.check("ip-a")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("ip-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("ip-a"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("请求过于频繁");
        assertThatCode(() -> limiter.check("ip-b")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("窗口滚动后重新计数")
    void windowResets() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(1, 60_000, clock);
        assertThatCode(() -> limiter.check("ip-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("ip-a")).isInstanceOf(ApiException.class);
        clock.advance(60_000);
        assertThatCode(() -> limiter.check("ip-a")).doesNotThrowAnyException();
    }
}
