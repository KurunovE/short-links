package com.egorkurunov.shortlinks;

import static org.assertj.core.api.Assertions.assertThat;

import com.egorkurunov.shortlinks.application.port.in.CreateLink;
import com.egorkurunov.shortlinks.application.port.in.ResolveLink;
import com.egorkurunov.shortlinks.application.port.in.UpdateLink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "cache.enabled=true",
            "cache.host=127.0.0.1",
            "cache.port=1",
            "cache.connect-timeout=100ms",
            "cache.command-timeout=100ms",
            "app.public-base-url=https://sho.rt"
        })
class ApplicationIT {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16.13-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    private final CreateLink create;
    private final ResolveLink resolve;
    private final UpdateLink update;

    @Test
    void applicationStartsAndServesWithRedisUnavailable() throws Exception {
        var link = create.create("https://example.org/old", "startup");
        assertThat(resolve.resolve(link.code().value()).value()).endsWith("/old");
        update.update("startup", "https://example.org/new");
        assertThat(resolve.resolve("startup").value()).endsWith("/new");
        var client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();
        var response =
                client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/startup"))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var health =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:"
                                                        + managementPort
                                                        + "/actuator/health/readiness"))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("https://example.org/new");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
}
