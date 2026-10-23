package com.egorkurunov.shortlinks.domain.error;

public final class CodeConflictException extends RuntimeException {
    public CodeConflictException() {
        super("CodeConflict");
    }
}
