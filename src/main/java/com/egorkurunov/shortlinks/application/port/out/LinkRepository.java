package com.egorkurunov.shortlinks.application.port.out;

import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.time.Instant;
import java.util.Optional;

public interface LinkRepository {
    Link insert(Link link);

    Optional<Link> find(ShortCode code);

    Optional<Link> update(ShortCode code, TargetUrl url, Instant now);
}
