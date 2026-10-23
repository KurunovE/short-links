package com.egorkurunov.shortlinks.application.port.in;

import com.egorkurunov.shortlinks.domain.model.TargetUrl;

public interface ResolveLink {
    TargetUrl resolve(String code);
}
