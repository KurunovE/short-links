package com.egorkurunov.shortlinks.config;

import com.egorkurunov.shortlinks.adapter.out.cache.NoOpLinkCache;
import com.egorkurunov.shortlinks.adapter.out.cache.RedisLinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheSettings.class)
public class CacheConfig {
    @Bean
    @ConditionalOnProperty(name = "cache.enabled", havingValue = "false", matchIfMissing = true)
    LinkCache noOpLinkCache() {
        return new NoOpLinkCache();
    }

    @Bean
    @ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
    LettuceConnectionFactory redisConnectionFactory(CacheSettings settings) {
        var server = new RedisStandaloneConfiguration(settings.host(), settings.port());
        if (settings.password() != null && !settings.password().isBlank()) {
            server.setPassword(settings.password());
        }
        var options =
                ClientOptions.builder()
                        .socketOptions(
                                SocketOptions.builder()
                                        .connectTimeout(settings.connectTimeout())
                                        .build())
                        .build();
        var client =
                LettuceClientConfiguration.builder()
                        .commandTimeout(settings.commandTimeout())
                        .shutdownTimeout(Duration.ZERO)
                        .clientOptions(options)
                        .build();
        return new LettuceConnectionFactory(server, client);
    }

    @Bean
    @ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
    StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    @ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
    LinkCache redisLinkCache(
            StringRedisTemplate redis, CacheSettings settings, MeterRegistry metrics) {
        var config =
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(settings.failureWindow())
                        .minimumNumberOfCalls(settings.failureWindow())
                        .failureRateThreshold(settings.failureRateThreshold())
                        .waitDurationInOpenState(settings.openDuration())
                        .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                        .recordExceptions(DataAccessException.class)
                        .build();
        return new RedisLinkCache(
                redis,
                CircuitBreaker.of("redis", config),
                settings.positiveTtl(),
                settings.negativeTtl(),
                metrics);
    }
}
