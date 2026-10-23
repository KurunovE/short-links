package com.egorkurunov.shortlinks.application.service;

import com.egorkurunov.shortlinks.application.port.in.ResolveLink;
import com.egorkurunov.shortlinks.application.port.out.CacheHit;
import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.domain.error.LinkNotFoundException;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

public final class ResolveLinkService implements ResolveLink {
    private final LinkRepository repository;
    private final LinkCache cache;
    private final ConcurrentHashMap<ShortCode, CompletableFuture<TargetUrl>> flights =
            new ConcurrentHashMap<>();

    public ResolveLinkService(LinkRepository repository, LinkCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Override
    public TargetUrl resolve(String code) {
        ShortCode key = new ShortCode(code);
        CacheResult cached = cache.get(key);
        if (cached instanceof CacheHit hit) {
            return hit.url();
        }
        if (cached == CacheResult.Missing.INSTANCE) {
            throw new LinkNotFoundException();
        }
        var mine = new CompletableFuture<TargetUrl>();
        var existing = flights.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                if (e.getCause() instanceof Error cause) {
                    throw cause;
                }
                throw e;
            }
        }
        try {
            cached = cache.get(key);
            TargetUrl result;
            if (cached instanceof CacheHit hit) {
                result = hit.url();
            } else if (cached == CacheResult.Missing.INSTANCE) {
                throw new LinkNotFoundException();
            } else {
                var stored = repository.find(key);
                if (stored.isEmpty()) {
                    cache.putMissing(key);
                    throw new LinkNotFoundException();
                }
                result = stored.get().url();
                cache.put(key, result);
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            flights.remove(key, mine);
        }
    }
}
