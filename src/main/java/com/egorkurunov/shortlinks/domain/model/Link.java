package com.egorkurunov.shortlinks.domain.model;

import java.time.Instant;
import java.util.Objects;

public record Link(ShortCode code, TargetUrl url, Instant createdAt, Instant updatedAt) {
    public Link {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
