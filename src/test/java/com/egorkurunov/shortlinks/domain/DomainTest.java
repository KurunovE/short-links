package com.egorkurunov.shortlinks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class DomainTest {
    @Test
    void acceptsHttpWithoutChangingMeaning() {
        for (String url :
                new String[] {
                    "HTTP://example.org/a?x=%2F#frag",
                    "https://example.org:8443/путь",
                    "http://127.0.0.1/",
                    "https://[::1]/"
                }) {
            assertThat(new TargetUrl(url).value()).isEqualTo(url);
        }
    }

    @Test
    void acceptsExactly2048Characters() {
        String prefix = "https://example.org/";
        assertThat(new TargetUrl(prefix + "x".repeat(2048 - prefix.length())).value())
                .hasSize(2048);
    }

    @Test
    void rejectsInvalidUrls() {
        for (String url :
                new String[] {
                    "",
                    " ",
                    "ftp://example.org",
                    "https:///path",
                    "/relative",
                    "http://",
                    "https://example.org/\r\nX:foo",
                    "https://example.org/\u007f",
                    "https://example.org/%zz",
                    "https://example.org/" + "x".repeat(2048)
                }) {
            assertThatThrownBy(() -> new TargetUrl(url)).isInstanceOf(InvalidInputException.class);
        }
        assertThatThrownBy(() -> new TargetUrl(null)).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void incompleteLinkCannotReachStorage() {
        var code = new ShortCode("valid12");
        var url = new TargetUrl("https://example.org/");
        var now = Instant.now();
        for (ThrowingCallable operation :
                new ThrowingCallable[] {
                    () -> new Link(null, url, now, now),
                    () -> new Link(code, null, now, now),
                    () -> new Link(code, url, null, now),
                    () -> new Link(code, url, now, null)
                }) {
            assertThatThrownBy(operation).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void validatesAliasBoundariesAndCase() {
        assertThat(new ShortCode("Ab_").value()).isEqualTo("Ab_");
        assertThat(new ShortCode("a".repeat(32)).value()).hasSize(32);
        for (String code :
                new String[] {
                    "",
                    " ",
                    "ab",
                    "a".repeat(33),
                    "рус",
                    "a.b",
                    "API",
                    "Health",
                    "metrics",
                    "actuator",
                    "swagger-ui",
                    "V3"
                }) {
            assertThatThrownBy(() -> new ShortCode(code)).isInstanceOf(InvalidInputException.class);
        }
    }
}
