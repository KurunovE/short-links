package com.egorkurunov.shortlinks.domain.model;

import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import java.net.URI;
import java.net.URISyntaxException;

public record TargetUrl(String value) {
    public static final int MAX_LENGTH = 2048;

    public TargetUrl {
        if (value == null
                || value.length() > MAX_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidInputException();
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()) {
                throw new InvalidInputException();
            }
        } catch (URISyntaxException e) {
            throw new InvalidInputException();
        }
    }
}
