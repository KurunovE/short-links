package com.egorkurunov.shortlinks.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @Test
    void restoresOuterRequestIdAfterSuccessfulRequest() throws Exception {
        MDC.put("requestId", "outer-request");
        try {
            var request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER, "inner-request");
            new RequestIdFilter()
                    .doFilter(
                            request,
                            new MockHttpServletResponse(),
                            (req, res) ->
                                    assertThat(MDC.get("requestId")).isEqualTo("inner-request"));
            assertThat(MDC.get("requestId")).isEqualTo("outer-request");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void restoresOuterRequestIdWhenChainFails() {
        MDC.put("requestId", "outer-request");
        try {
            var request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER, "inner-request");
            assertThatThrownBy(
                            () ->
                                    new RequestIdFilter()
                                            .doFilter(
                                                    request,
                                                    new MockHttpServletResponse(),
                                                    (req, res) -> {
                                                        throw new ServletException("chain failed");
                                                    }))
                    .isInstanceOf(ServletException.class);
            assertThat(MDC.get("requestId")).isEqualTo("outer-request");
        } finally {
            MDC.remove("requestId");
        }
    }
}
