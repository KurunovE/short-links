package com.egorkurunov.shortlinks.application.port.in;

import com.egorkurunov.shortlinks.domain.model.Link;

public interface CreateLink {
    Link create(String url, String alias);
}
