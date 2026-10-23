package com.egorkurunov.shortlinks.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CacheSettingsTest {
    @Test
    void diagnosticsCannotRevealRedisPassword() {
        var settings =
                new CacheSettings(
                        true,
                        "localhost",
                        6379,
                        "secret-redis-password",
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(5),
                        Duration.ofMillis(200),
                        Duration.ofMillis(200),
                        10,
                        Duration.ofSeconds(5),
                        50,
                        2);
        assertThat(settings.toString())
                .doesNotContain("secret-redis-password")
                .contains("localhost");
    }
}
