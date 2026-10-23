package com.egorkurunov.shortlinks.config;

import com.egorkurunov.shortlinks.adapter.in.web.PublicBaseUrl;
import com.egorkurunov.shortlinks.application.port.in.CreateLink;
import com.egorkurunov.shortlinks.application.port.in.ResolveLink;
import com.egorkurunov.shortlinks.application.port.in.UpdateLink;
import com.egorkurunov.shortlinks.application.port.out.CodeGenerator;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.application.service.CreateLinkService;
import com.egorkurunov.shortlinks.application.service.ResolveLinkService;
import com.egorkurunov.shortlinks.application.service.UpdateLinkService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BeanConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CreateLink createLink(
            LinkRepository repository, LinkCache cache, CodeGenerator generator, Clock clock) {
        return new CreateLinkService(repository, cache, generator, clock);
    }

    @Bean
    ResolveLink resolveLink(LinkRepository repository, LinkCache cache) {
        return new ResolveLinkService(repository, cache);
    }

    @Bean
    UpdateLink updateLink(LinkRepository repository, LinkCache cache, Clock clock) {
        return new UpdateLinkService(repository, cache, clock);
    }

    @Bean
    PublicBaseUrl publicBaseUrl(@Value("${app.public-base-url}") String value) {
        return new PublicBaseUrl(value);
    }
}
