package com.egorkurunov.shortlinks.application.service;

import com.egorkurunov.shortlinks.application.port.in.UpdateLink;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.domain.error.LinkNotFoundException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.time.Clock;

public final class UpdateLinkService implements UpdateLink {
    private final LinkRepository repository;
    private final LinkCache cache;
    private final Clock clock;

    public UpdateLinkService(LinkRepository repository, LinkCache cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    public Link update(String code, String url) {
        ShortCode key = new ShortCode(code);
        TargetUrl target = new TargetUrl(url);
        Link result =
                repository
                        .update(key, target, clock.instant())
                        .orElseThrow(LinkNotFoundException::new);
        cache.evict(key);
        return result;
    }
}
