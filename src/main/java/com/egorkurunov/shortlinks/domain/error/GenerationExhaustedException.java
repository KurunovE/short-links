package com.egorkurunov.shortlinks.domain.error;

public final class GenerationExhaustedException extends RuntimeException {
    public GenerationExhaustedException() {
        super("GenerationExhausted");
    }
}
