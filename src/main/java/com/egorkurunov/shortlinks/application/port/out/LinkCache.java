package com.egorkurunov.shortlinks.application.port.out;

import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;

public interface LinkCache {
    CacheResult get(ShortCode code);

    void put(ShortCode code, TargetUrl url);

    void putMissing(ShortCode code);

    void evict(ShortCode code);
}
