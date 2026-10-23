package com.egorkurunov.shortlinks.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final Pattern VALID_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_REQUEST_ID_LENGTH + "}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !VALID_REQUEST_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        request.setAttribute("requestId", id);
        response.setHeader(HEADER, id);
        String previousId = MDC.get("requestId");
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previousId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousId);
            }
        }
    }
}
