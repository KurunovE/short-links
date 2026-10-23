package com.egorkurunov.shortlinks.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.egorkurunov.shortlinks.application.port.out.CacheHit;
import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisLinkCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");
    final RedisLinkCache cache =
            new RedisLinkCache(
                    redis, breaker, Duration.ofSeconds(60), Duration.ofSeconds(5), metrics);
    final ShortCode key = new ShortCode("abc1234");

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void distinguishesPayloadsAndUsesSeparateTtls() {
        when(values.get("shortlinks:v1:abc1234"))
                .thenReturn(null, "missing", "url:https://example.org/", "unexpected");
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        assertThat(cache.get(key)).isEqualTo(CacheResult.missing());
        assertThat(cache.get(key))
                .isEqualTo(CacheResult.hit(new TargetUrl("https://example.org/")));
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        cache.put(key, new TargetUrl("https://example.org/"));
        cache.putMissing(key);
        verify(values)
                .set("shortlinks:v1:abc1234", "url:https://example.org/", Duration.ofSeconds(60));
        verify(values).set("shortlinks:v1:abc1234", "missing", Duration.ofSeconds(5));
    }

    @Test
    void readWriteAndEvictionFailuresFallBackAndCount() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        doThrow(new RedisConnectionFailureException("offline"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        cache.put(key, new TargetUrl("https://example.org/"));
        cache.putMissing(key);
        cache.evict(key);
        assertThat(metrics.get("shortlinks.cache.invalidation.errors").counter().count())
                .isEqualTo(1);
    }

    @Test
    void openCircuitSkipsRedisAndRecoversAfterReset() {
        breaker.transitionToOpenState();
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        verifyNoInteractions(values);
        breaker.reset();
        when(values.get(anyString())).thenReturn("url:https://example.org/");
        assertThat(cache.get(key)).isInstanceOf(CacheHit.class);
    }

    @Test
    void corruptCachedUrlIsAMissRatherThanAClientValidationError() {
        when(values.get(anyString())).thenReturn("url:not-a-url");
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        assertThat(
                        metrics.get("shortlinks.cache.errors")
                                .tag("operation", "decode")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(metrics.find("shortlinks.cache.requests").tag("outcome", "hit").counter())
                .isNull();
    }

    @Test
    void programmingErrorsAreNeverMaskedAsRedisOutages() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("bug"));
        assertThatThrownBy(() -> cache.get(key)).isInstanceOf(IllegalStateException.class);
    }
}
