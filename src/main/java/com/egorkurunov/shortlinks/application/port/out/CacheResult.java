package com.egorkurunov.shortlinks.application.port.out;

import com.egorkurunov.shortlinks.domain.model.TargetUrl;

public sealed interface CacheResult permits CacheHit, CacheResult.Missing, CacheResult.Miss {
    enum Missing implements CacheResult {
        INSTANCE
    }

    enum Miss implements CacheResult {
        INSTANCE
    }

    static CacheResult hit(TargetUrl url) {
        return new CacheHit(url);
    }

    static CacheResult missing() {
        return Missing.INSTANCE;
    }

    static CacheResult miss() {
        return Miss.INSTANCE;
    }
}
