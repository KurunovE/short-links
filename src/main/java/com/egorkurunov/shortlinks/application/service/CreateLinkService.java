package com.egorkurunov.shortlinks.application.service;

import com.egorkurunov.shortlinks.application.port.in.CreateLink;
import com.egorkurunov.shortlinks.application.port.out.CodeGenerator;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.GenerationExhaustedException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.time.Clock;

public final class CreateLinkService implements CreateLink {
    private static final int MAX_INSERT_ATTEMPTS = 5;
    private final LinkRepository repository;
    private final LinkCache cache;
    private final CodeGenerator generator;
    private final Clock clock;

    public CreateLinkService(
            LinkRepository repository, LinkCache cache, CodeGenerator generator, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.generator = generator;
        this.clock = clock;
    }

    @Override
    public Link create(String url, String alias) {
        TargetUrl target = new TargetUrl(url);
        if (alias != null) {
            return insert(new ShortCode(alias), target);
        }
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            try {
                return insert(generator.next(), target);
            } catch (CodeConflictException collision) {

            }
        }
        throw new GenerationExhaustedException();
    }

    private Link insert(ShortCode code, TargetUrl url) {
        var now = clock.instant();
        Link result = repository.insert(new Link(code, url, now, now));
        cache.evict(code);
        return result;
    }
}
