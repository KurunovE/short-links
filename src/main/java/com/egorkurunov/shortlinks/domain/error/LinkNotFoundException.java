package com.egorkurunov.shortlinks.domain.error;

public final class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException() {
        super("LinkNotFound");
    }
}
