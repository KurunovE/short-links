package com.egorkurunov.shortlinks.application.port.out;

import com.egorkurunov.shortlinks.domain.model.ShortCode;

public interface CodeGenerator {
    ShortCode next();
}
