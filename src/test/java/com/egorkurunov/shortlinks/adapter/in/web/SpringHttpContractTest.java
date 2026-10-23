package com.egorkurunov.shortlinks.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.egorkurunov.shortlinks.adapter.out.cache.NoOpLinkCache;
import com.egorkurunov.shortlinks.adapter.out.codegen.Base62CodeGenerator;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.config.BeanConfig;
import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class
})
@WebMvcTest(controllers = LinkController.class, properties = "app.public-base-url=https://sho.rt")
@Import({BeanConfig.class, Base62CodeGenerator.class, SpringHttpContractTest.TestPorts.class})
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SpringHttpContractTest {
    private final MockMvc mvc;

    @Test
    void annotatedComponentsCreateResolveAndUpdateWithExternalDtos() throws Exception {
        mvc.perform(
                        post("/api/links")
                                .header("X-Request-ID", "di_test")
                                .contentType("application/json")
                                .content(
                                        "{\"url\":\"https://example.org/old\",\"alias\":\"springdi\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "https://sho.rt/springdi"))
                .andExpect(header().string("X-Request-ID", "di_test"));
        mvc.perform(get("/springdi"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.org/old"));
        mvc.perform(
                        put("/api/links/springdi")
                                .contentType("application/json")
                                .content("{\"url\":\"https://example.org/new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value("https://sho.rt/springdi"));
        mvc.perform(get("/springdi"))
                .andExpect(header().string("Location", "https://example.org/new"));
        mvc.perform(post("/api/links").contentType("application/json").content("{\"url\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void generatedOpenApiMatchesTheHttpContract() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        for (String code : new String[] {"201", "400", "409", "503", "500"}) {
            response.andExpect(
                    jsonPath("$.paths['/api/links'].post.responses['" + code + "']").exists());
        }
        for (String code : new String[] {"200", "400", "404", "503", "500"}) {
            response.andExpect(
                    jsonPath("$.paths['/api/links/{code}'].put.responses['" + code + "']")
                            .exists());
        }
        for (String code : new String[] {"302", "400", "404", "503", "500"}) {
            response.andExpect(
                    jsonPath("$.paths['/{code}'].get.responses['" + code + "']").exists());
        }
        response.andExpect(jsonPath("$.paths['/api/links'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/{code}'].get.responses['200']").doesNotExist())
                .andExpect(
                        jsonPath(
                                "$.components.schemas.CreateLinkRequest.required",
                                Matchers.hasItem("url")))
                .andExpect(
                        jsonPath(
                                "$.components.schemas.UpdateLinkRequest.required",
                                Matchers.hasItem("url")))
                .andExpect(
                        jsonPath("$.components.schemas.CreateLinkRequest.properties.alias")
                                .exists())
                .andExpect(
                        jsonPath("$.components.schemas.LinkResponse.properties.shortUrl").exists());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPorts {
        @Bean
        LinkCache cache() {
            return new NoOpLinkCache();
        }

        @Bean
        MeterRegistry metrics() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LinkRepository repository() {
            return new LinkRepository() {
                private final ConcurrentHashMap<ShortCode, Link> links = new ConcurrentHashMap<>();

                public Link insert(Link link) {
                    if (links.putIfAbsent(link.code(), link) != null) {
                        throw new CodeConflictException();
                    }
                    return link;
                }

                public Optional<Link> find(ShortCode code) {
                    return Optional.ofNullable(links.get(code));
                }

                public Optional<Link> update(ShortCode code, TargetUrl url, Instant now) {
                    return Optional.ofNullable(
                            links.computeIfPresent(
                                    code, (key, old) -> new Link(key, url, old.createdAt(), now)));
                }
            };
        }
    }
}
