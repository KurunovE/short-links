package com.egorkurunov.shortlinks.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisLinkCacheIT {
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.8-alpine").withExposedPorts(6379);

    LettuceConnectionFactory factory;
    RedisLinkCache cache;
    StringRedisTemplate template;
    final ShortCode key = new ShortCode("abc1234");
    final TargetUrl url = new TargetUrl("https://example.org/");

    @BeforeEach
    void setup() {
        var server = new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        var client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(100))
                        .clientOptions(
                                ClientOptions.builder()
                                        .socketOptions(
                                                SocketOptions.builder()
                                                        .connectTimeout(Duration.ofMillis(100))
                                                        .build())
                                        .build())
                        .shutdownTimeout(Duration.ZERO)
                        .build();
        factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        var breaker =
                CircuitBreaker.of(
                        "redis-it",
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(2)
                                .minimumNumberOfCalls(2)
                                .waitDurationInOpenState(Duration.ofMillis(100))
                                .recordExceptions(org.springframework.dao.DataAccessException.class)
                                .build());
        cache =
                new RedisLinkCache(
                        template,
                        breaker,
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(5),
                        new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanup() {
        factory.destroy();
    }

    @Test
    void storesThreeStatesWithIndependentTtlsAndExpiry() {
        assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
        cache.putMissing(key);
        assertThat(cache.get(key)).isEqualTo(CacheResult.missing());
        assertThat(template.getExpire("shortlinks:v1:abc1234")).isBetween(1L, 5L);
        cache.evict(key);
        cache.put(key, url);
        assertThat(cache.get(key)).isEqualTo(CacheResult.hit(url));
        assertThat(template.getExpire("shortlinks:v1:abc1234")).isBetween(50L, 60L);
        template.expire("shortlinks:v1:abc1234", Duration.ofMillis(1));
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(cache.get(key)).isEqualTo(CacheResult.miss()));
    }

    @Test
    void unavailableRedisDoesNotBreakCacheOperationsAndRecovers() {
        var docker = redis.getDockerClient();
        // Keep the endpoint stable while Redis stops responding, as in a network outage.
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertThat(cache.get(key)).isEqualTo(CacheResult.miss());
            cache.put(key, url);
            cache.putMissing(key);
            cache.evict(key);
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> {
                            cache.put(key, url);
                            assertThat(cache.get(key)).isEqualTo(CacheResult.hit(url));
                        });
    }
}
