package com.egorkurunov.shortlinks.adapter.out.cache;

import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisLinkCache implements LinkCache {

    private static final Logger LOG = LoggerFactory.getLogger(RedisLinkCache.class);
    private static final String PREFIX = "shortlinks:v1:";
    private static final String ABSENT = "missing";
    private static final String URL_PREFIX = "url:";
    private final StringRedisTemplate redis;
    private final CircuitBreaker breaker;
    private final Duration positiveTtl;
    private final Duration negativeTtl;
    private final MeterRegistry metrics;

    public RedisLinkCache(
            StringRedisTemplate redis,
            CircuitBreaker breaker,
            Duration positiveTtl,
            Duration negativeTtl,
            MeterRegistry metrics) {
        this.redis = redis;
        this.breaker = breaker;
        this.positiveTtl = positiveTtl;
        this.negativeTtl = negativeTtl;
        this.metrics = metrics;
        breaker.getEventPublisher()
                .onStateTransition(
                        event -> {
                            LOG.warn("Redis circuit state={}", event.getStateTransition());
                            metrics.counter(
                                            "shortlinks.cache.circuit.transitions",
                                            "transition",
                                            event.getStateTransition().name())
                                    .increment();
                        });
        metrics.gauge(
                "shortlinks.cache.circuit.open",
                breaker,
                b -> b.getState() == CircuitBreaker.State.OPEN ? 1 : 0);
    }

    @Override
    public CacheResult get(ShortCode code) {
        return guarded(
                "get",
                () -> {
                    String value = redis.opsForValue().get(PREFIX + code.value());
                    if (value == null) {
                        count("miss");
                        return CacheResult.miss();
                    }
                    if (ABSENT.equals(value)) {
                        count("negative_hit");
                        return CacheResult.missing();
                    }
                    if (value.startsWith(URL_PREFIX)) {
                        try {
                            TargetUrl url = new TargetUrl(value.substring(URL_PREFIX.length()));
                            count("hit");
                            return CacheResult.hit(url);
                        } catch (InvalidInputException corruptedPayload) {

                        }
                    }
                    metrics.counter("shortlinks.cache.errors", "operation", "decode").increment();
                    LOG.warn("Redis payload invalid; reloading from PostgreSQL");
                    count("miss");
                    return CacheResult.miss();
                },
                CacheResult.miss());
    }

    @Override
    public void put(ShortCode code, TargetUrl url) {
        guarded(
                "put",
                () -> {
                    redis.opsForValue()
                            .set(PREFIX + code.value(), URL_PREFIX + url.value(), positiveTtl);
                    return true;
                },
                false);
    }

    @Override
    public void putMissing(ShortCode code) {
        guarded(
                "put_missing",
                () -> {
                    redis.opsForValue().set(PREFIX + code.value(), ABSENT, negativeTtl);
                    return true;
                },
                false);
    }

    @Override
    public void evict(ShortCode code) {
        guarded("evict", () -> redis.delete(PREFIX + code.value()), false);
    }

    private void count(String outcome) {
        metrics.counter("shortlinks.cache.requests", "outcome", outcome).increment();
    }

    private <T> T guarded(String operation, Supplier<T> action, T fallback) {
        try {
            return breaker.executeSupplier(action);
        } catch (DataAccessException | CallNotPermittedException e) {
            metrics.counter("shortlinks.cache.errors", "operation", operation).increment();
            if ("evict".equals(operation)) {
                metrics.counter("shortlinks.cache.invalidation.errors").increment();
            }
            LOG.warn("Redis operation={} failed type={}", operation, e.getClass().getSimpleName());
            return fallback;
        }
    }
}
