package com.egorkurunov.shortlinks.adapter.out.cache;

import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;

public final class NoOpLinkCache implements LinkCache {
    @Override
    public CacheResult get(ShortCode code) {
        return CacheResult.miss();
    }

    @Override
    public void put(ShortCode code, TargetUrl url) {}

    @Override
    public void putMissing(ShortCode code) {}

    @Override
    public void evict(ShortCode code) {}
}
