package com.egorkurunov.shortlinks.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.egorkurunov.shortlinks.adapter.out.cache.NoOpLinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.application.service.CreateLinkService;
import com.egorkurunov.shortlinks.application.service.ResolveLinkService;
import com.egorkurunov.shortlinks.application.service.UpdateLinkService;
import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HttpContractTest {
    final Map<ShortCode, Link> data = new HashMap<>();
    final LinkRepository repo =
            new LinkRepository() {
                public Link insert(Link link) {
                    if (data.putIfAbsent(link.code(), link) != null) {
                        throw new CodeConflictException();
                    }
                    return link;
                }

                public Optional<Link> find(ShortCode key) {
                    return Optional.ofNullable(data.get(key));
                }

                public Optional<Link> update(ShortCode key, TargetUrl url, Instant now) {
                    return Optional.ofNullable(
                            data.computeIfPresent(
                                    key, (k, v) -> new Link(k, url, v.createdAt(), now)));
                }
            };
    MockMvc mvc;

    @BeforeEach
    void setup() {
        var cache = new NoOpLinkCache();
        var clock = Clock.systemUTC();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new LinkController(
                                        new CreateLinkService(
                                                repo, cache, () -> new ShortCode("abc1234"), clock),
                                        new UpdateLinkService(repo, cache, clock),
                                        new ResolveLinkService(repo, cache),
                                        new PublicBaseUrl("https://sho.rt"),
                                        new SimpleMeterRegistry()))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .addFilters(new RequestIdFilter())
                        .build();
    }

    @Test
    void createRedirectUpdateAndNoStore() throws Exception {
        mvc.perform(
                        post("/api/links")
                                .header("Host", "attacker.example")
                                .header("X-Request-ID", "req_123")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org/old\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "https://sho.rt/abc1234"))
                .andExpect(header().string("X-Request-ID", "req_123"))
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/abc1234"));
        mvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.org/old"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(
                        put("/api/links/abc1234")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org/new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.org/new"));
        mvc.perform(get("/abc1234"))
                .andExpect(header().string("Location", "https://example.org/new"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void invalidRequestsMissingAliasConflictAndGenerationExhaustion() throws Exception {
        for (String body :
                new String[] {
                    "{",
                    "{}",
                    "{\"url\":\"ftp://example.org\"}",
                    "{\"url\":\"https://example.org\",\"alias\":\" \"}"
                }) {
            mvc.perform(post("/api/links").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(
                        put("/api/links/missing")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org\",\"alias\":\"abc1234\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org\",\"alias\":\"abc1234\"}"))
                .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void extractedDtosKeepUrlAndAliasBoundaries() throws Exception {
        String prefix = "https://example.org/";
        String longestUrl = prefix + "x".repeat(2048 - prefix.length());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content("{\"url\":\"" + longestUrl + "\",\"alias\":\"urlmax\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content("{\"url\":\"" + longestUrl + "x\",\"alias\":\"overurl\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content(
                                        "{\"url\":\"https://example.org/\",\"alias\":\""
                                                + "a".repeat(32)
                                                + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json")
                                .content(
                                        "{\"url\":\"https://example.org/\",\"alias\":\""
                                                + "a".repeat(33)
                                                + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestIdIsBoundedAndBaseUrlCannotCarryCredentials() throws Exception {
        mvc.perform(get("/missing").header("X-Request-ID", "x".repeat(65)))
                .andExpect(
                        header().string("X-Request-ID", Matchers.matchesPattern("[a-f0-9-]{36}")));
        assertThat(MDC.get("requestId")).isNull();
        for (String origin :
                new String[] {
                    "https://user:pass@example.org",
                    "https://example.org/path",
                    "https://example.org?query=1",
                    "ftp://example.org",
                    "https://example.org:65536",
                    "https://example.org:0"
                }) {
            assertThatThrownBy(() -> new PublicBaseUrl(origin))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unicodeTargetKeepsStoredValueAndUsesAsciiLocationOnTheWire() throws Exception {
        mvc.perform(
                        post("/api/links")
                                .contentType("application/json;charset=UTF-8")
                                .content(
                                        "{\"url\":\"https://example.org/путь\",\"alias\":\"unicode\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://example.org/путь"));
        mvc.perform(get("/unicode"))
                .andExpect(status().isFound())
                .andExpect(
                        header().string(
                                        "Location",
                                        "https://example.org/%D0%BF%D1%83%D1%82%D1%8C"));
    }

    @Test
    void unsupportedMethodsAndMediaTypesKeepTheirClientErrorStatus() throws Exception {
        mvc.perform(delete("/api/links/abc1234"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", Matchers.containsString("PUT")));
        mvc.perform(post("/api/links").contentType("text/plain").content("invalid"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void infrastructureAndUnexpectedFailuresAreSafe() throws Exception {
        var broken =
                MockMvcBuilders.standaloneSetup(
                                new LinkController(
                                        (url, alias) -> {
                                            throw new StorageUnavailableException();
                                        },
                                        (code, url) -> {
                                            throw new IllegalStateException("secret sql url");
                                        },
                                        code -> {
                                            throw new StorageUnavailableException();
                                        },
                                        new PublicBaseUrl("https://sho.rt"),
                                        new SimpleMeterRegistry()))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .addFilters(new RequestIdFilter())
                        .build();
        broken.perform(get("/abc1234"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Service temporarily unavailable"));
        broken.perform(
                        put("/api/links/abc1234")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }
}
