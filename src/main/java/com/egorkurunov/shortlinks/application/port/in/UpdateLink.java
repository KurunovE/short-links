package com.egorkurunov.shortlinks.application.port.in;

import com.egorkurunov.shortlinks.domain.model.Link;

public interface UpdateLink {
    Link update(String code, String url);
}
