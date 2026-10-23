package com.egorkurunov.shortlinks.application.port.out;

import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.util.Objects;

public record CacheHit(TargetUrl url) implements CacheResult {
    public CacheHit {
        Objects.requireNonNull(url, "url");
    }
}
