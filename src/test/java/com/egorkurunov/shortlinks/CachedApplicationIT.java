package com.egorkurunov.shortlinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "cache.enabled=true",
            "cache.connect-timeout=100ms",
            "cache.command-timeout=100ms",
            "cache.failure-window=2",
            "cache.open-duration=100ms",
            "cache.half-open-calls=2",
            "app.public-base-url=https://sho.rt",
            "springdoc.api-docs.enabled=true"
        })
class CachedApplicationIT {
    private static final String CACHE_PREFIX = "shortlinks:v1:";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(20);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16.13-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.8-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("cache.host", redis::getHost);
        registry.add("cache.port", () -> redis.getMappedPort(6379));
    }

    private final ObjectMapper mapper;
    private final StringRedisTemplate template;
    private final MeterRegistry metrics;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

    @LocalServerPort int port;

    @LocalManagementPort int managementPort;

    @Test
    void httpCacheAsideSurvivesRedisOutageAndRecovers() throws Exception {
        String code = "cachedflow";
        String key = CACHE_PREFIX + code;
        String original = "https://example.invalid/original";
        String updated = "https://example.invalid/updated";
        String duringOutage = "https://example.invalid/during-outage";
        String recoveryCode = "recoveryflow";
        String recovered = "https://example.invalid/recovered";

        assertThat(request("GET", "/" + code, null).statusCode()).isEqualTo(404);
        assertThat(template.opsForValue().get(key)).isEqualTo("missing");
        assertThat(request("GET", "/" + code, null).statusCode()).isEqualTo(404);

        HttpResponse<String> created =
                request("POST", "/api/links", Map.of("url", original, "alias", code));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).contains("https://sho.rt/" + code);
        assertThat(template.hasKey(key)).isFalse();
        assertRedirect(code, original);
        assertThat(template.opsForValue().get(key)).isEqualTo("url:" + original);
        double hitsBefore = cacheHits();
        assertRedirect(code, original);
        assertThat(cacheHits()).isEqualTo(hitsBefore + 1);

        assertThat(request("PUT", "/api/links/" + code, Map.of("url", updated)).statusCode())
                .isEqualTo(200);
        assertThat(template.hasKey(key)).isFalse();
        assertRedirect(code, updated);
        assertThat(template.opsForValue().get(key)).isEqualTo("url:" + updated);

        var docker = redis.getDockerClient();
        // Keep the endpoint stable while Redis stops responding, as in a network outage.
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            assertThat(
                            request("PUT", "/api/links/" + code, Map.of("url", duringOutage))
                                    .statusCode())
                    .isEqualTo(200);
            assertRedirect(code, duringOutage);
            assertThat(
                            request(
                                            "POST",
                                            "/api/links",
                                            Map.of("url", recovered, "alias", recoveryCode))
                                    .statusCode())
                    .isEqualTo(201);
            assertReadinessUp();
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }

        // The original key may remain stale until TTL after a failed evict. Use a new
        // alias to prove cache recovery without claiming stronger consistency.
        await().atMost(RECOVERY_TIMEOUT)
                .ignoreExceptionsInstanceOf(DataAccessException.class)
                .untilAsserted(
                        () -> {
                            assertRedirect(recoveryCode, recovered);
                            assertThat(template.opsForValue().get(CACHE_PREFIX + recoveryCode))
                                    .isEqualTo("url:" + recovered);
                        });
        double recoveredHits = cacheHits();
        assertRedirect(recoveryCode, recovered);
        assertThat(cacheHits()).isEqualTo(recoveredHits + 1);
        assertReadinessUp();
    }

    @Test
    void openApiDescribesActualRequestFieldsAndSuccessAndErrorStatuses() throws Exception {
        HttpResponse<String> response = request("GET", "/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = mapper.readTree(response.body());
        JsonNode paths = document.path("paths");
        assertResponses(paths.path("/api/links").path("post"), "201", "400", "409", "503", "500");
        assertResponses(
                paths.path("/api/links/{code}").path("put"), "200", "400", "404", "503", "500");
        assertResponses(paths.path("/{code}").path("get"), "302", "400", "404", "503", "500");

        assertThat(paths.path("/api/links").path("post").path("responses").has("200")).isFalse();
        assertThat(paths.path("/{code}").path("get").path("responses").has("200")).isFalse();

        JsonNode schemas = document.path("components").path("schemas");
        assertRequiredUrl(schemas.path("CreateLinkRequest"));
        assertThat(schemas.path("CreateLinkRequest").path("properties").has("alias")).isTrue();
        assertRequiredUrl(schemas.path("UpdateLinkRequest"));
        JsonNode fields = schemas.path("LinkResponse").path("properties");
        assertThat(fields.has("code")).isTrue();
        assertThat(fields.has("url")).isTrue();
        assertThat(fields.has("shortUrl")).isTrue();
    }

    private void assertResponses(JsonNode operation, String... statuses) {
        assertThat(operation.isMissingNode()).isFalse();
        for (String status : statuses) {
            assertThat(operation.path("responses").has(status))
                    .as("documented HTTP status %s", status)
                    .isTrue();
        }
    }

    private void assertRequiredUrl(JsonNode schema) {
        assertThat(schema.path("properties").has("url")).isTrue();
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        assertThat(required).contains("url").doesNotContain("alias");
    }

    private double cacheHits() {
        Counter counter = metrics.find("shortlinks.cache.requests").tag("outcome", "hit").counter();
        return counter == null ? 0 : counter.count();
    }

    private void assertRedirect(String code, String target) throws Exception {
        HttpResponse<String> response = request("GET", "/" + code, null);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(target);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }

    private void assertReadinessUp() throws Exception {
        HttpResponse<String> response =
                send(managementPort, "GET", "/actuator/health/readiness", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("UP");
    }

    private HttpResponse<String> request(String method, String path, Map<String, String> body)
            throws Exception {
        return send(port, method, path, body);
    }

    private HttpResponse<String> send(
            int targetPort, String method, String path, Map<String, String> body) throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
                        .timeout(REQUEST_TIMEOUT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
