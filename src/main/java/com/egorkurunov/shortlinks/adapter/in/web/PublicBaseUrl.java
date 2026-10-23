package com.egorkurunov.shortlinks.adapter.in.web;

import java.net.URI;

public record PublicBaseUrl(String value) {

    private static final int MAX_PORT = 65535;

    public PublicBaseUrl {
        if (value == null) {
            throw new IllegalArgumentException("PUBLIC_BASE_URL is required");
        }
        URI uri = URI.create(value);
        if (!uri.isAbsolute()
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getPort() == 0
                || uri.getPort() > MAX_PORT
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTP origin");
        }
        value = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
