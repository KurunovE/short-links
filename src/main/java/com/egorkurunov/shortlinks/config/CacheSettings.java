package com.egorkurunov.shortlinks.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cache")
public record CacheSettings(
        boolean enabled,
        String host,
        int port,
        String password,
        Duration positiveTtl,
        Duration negativeTtl,
        Duration connectTimeout,
        Duration commandTimeout,
        int failureWindow,
        Duration openDuration,
        float failureRateThreshold,
        int halfOpenCalls) {
    private static final int MAX_PORT = 65535;
    private static final int MIN_FAILURE_WINDOW_SIZE = 2;
    private static final float MAX_FAILURE_RATE_PERCENT = 100;

    @Override
    public String toString() {
        return "CacheSettings[enabled=%s, host=%s, port=%d]".formatted(enabled, host, port);
    }

    public CacheSettings {
        if (port < 1 || port > MAX_PORT || failureWindow < MIN_FAILURE_WINDOW_SIZE) {
            throw new IllegalArgumentException("Invalid cache settings");
        }
        if (!Float.isFinite(failureRateThreshold)
                || failureRateThreshold <= 0
                || failureRateThreshold > MAX_FAILURE_RATE_PERCENT
                || halfOpenCalls < 1) {
            throw new IllegalArgumentException("Invalid circuit breaker settings");
        }
        for (Duration duration :
                new Duration[] {
                    positiveTtl, negativeTtl, connectTimeout, commandTimeout, openDuration
                }) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Cache durations must be positive");
            }
        }
    }
}
