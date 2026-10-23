package com.egorkurunov.shortlinks.domain.error;

public final class InvalidInputException extends RuntimeException {
    public InvalidInputException() {
        super("InvalidInput");
    }
}
