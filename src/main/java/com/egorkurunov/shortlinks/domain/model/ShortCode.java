package com.egorkurunov.shortlinks.domain.model;

import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record ShortCode(String value) {
    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Set<String> RESERVED =
            Set.of("api", "health", "metrics", "actuator", "swagger-ui", "v3");

    public ShortCode {
        if (value == null
                || value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH
                || !ALLOWED_CHARACTERS.matcher(value).matches()
                || RESERVED.contains(value.toLowerCase(Locale.ROOT))) {
            throw new InvalidInputException();
        }
    }
}
