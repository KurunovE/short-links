package com.egorkurunov.shortlinks.domain.error;

public final class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(Throwable cause) {
        super("StorageUnavailable", cause);
    }

    public StorageUnavailableException() {
        super("StorageUnavailable");
    }
}
