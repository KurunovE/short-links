package com.egorkurunov.shortlinks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.egorkurunov.shortlinks.application.port.out.CacheResult;
import com.egorkurunov.shortlinks.application.port.out.CodeGenerator;
import com.egorkurunov.shortlinks.application.port.out.LinkCache;
import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.application.service.CreateLinkService;
import com.egorkurunov.shortlinks.application.service.ResolveLinkService;
import com.egorkurunov.shortlinks.application.service.UpdateLinkService;
import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.GenerationExhaustedException;
import com.egorkurunov.shortlinks.domain.error.InvalidInputException;
import com.egorkurunov.shortlinks.domain.error.LinkNotFoundException;
import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LinkServicesTest {
    final FakeRepository repo = new FakeRepository();
    final FakeCache cache = new FakeCache();
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    CreateLinkService creator(CodeGenerator generator) {
        return new CreateLinkService(repo, cache, generator, clock);
    }

    @Test
    void cacheHitMustContainATarget() {
        assertThatThrownBy(() -> CacheResult.hit(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void createsAliasAndClearsNegativeCacheWithoutWarming() {
        cache.values.put(new ShortCode("abc"), CacheResult.missing());
        Link link =
                creator(
                                () -> {
                                    throw new AssertionError();
                                })
                        .create("https://example.org/", "abc");
        assertThat(link.code().value()).isEqualTo("abc");
        assertThat(cache.values).isEmpty();
        assertThat(link.createdAt()).isEqualTo(clock.instant());
    }

    @Test
    void retriesOnlyGeneratedCollisionsAndStopsAfterFiveInserts() {
        creator(() -> new ShortCode("taken12")).create("https://example.org/", "taken12");
        AtomicInteger n = new AtomicInteger();
        assertThat(
                        creator(
                                        () ->
                                                new ShortCode(
                                                        n.incrementAndGet() == 1
                                                                ? "taken12"
                                                                : "fresh12"))
                                .create("https://example.org/", null)
                                .code()
                                .value())
                .isEqualTo("fresh12");
        n.set(0);
        assertThatThrownBy(
                        () ->
                                creator(
                                                () -> {
                                                    n.incrementAndGet();
                                                    return new ShortCode("taken12");
                                                })
                                        .create("https://example.org/", null))
                .isInstanceOf(GenerationExhaustedException.class);
        assertThat(n).hasValue(5);
        assertThatThrownBy(
                        () ->
                                creator(() -> new ShortCode("unused1"))
                                        .create("https://example.org/", "taken12"))
                .isInstanceOf(CodeConflictException.class);
    }

    @Test
    void fifthGeneratedInsertCanStillSucceed() {
        creator(() -> new ShortCode("taken12")).create("https://example.org/", "taken12");
        AtomicInteger attempts = new AtomicInteger();
        Link result =
                creator(() -> new ShortCode(attempts.incrementAndGet() < 5 ? "taken12" : "final12"))
                        .create("https://example.org/", null);
        assertThat(attempts).hasValue(5);
        assertThat(result.code().value()).isEqualTo("final12");
    }

    @Test
    void invalidInputNeverReachesStorageAndDatabaseFailureDoesNotRetry() {
        assertThatThrownBy(
                        () ->
                                creator(() -> new ShortCode("valid12"))
                                        .create("https://example.org/", " "))
                .isInstanceOf(InvalidInputException.class);
        assertThat(repo.data).isEmpty();
        repo.failure = new StorageUnavailableException();
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                creator(
                                                () -> {
                                                    n.incrementAndGet();
                                                    return new ShortCode("valid12");
                                                })
                                        .create("https://example.org/", null))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(n).hasValue(1);
    }

    @Test
    void resolveCachesPositiveAndNegativeAndUpdateInvalidates() {
        var create = creator(() -> new ShortCode("valid12"));
        var resolve = new ResolveLinkService(repo, cache);
        create.create("https://example.org/old", "valid12");
        assertThat(resolve.resolve("valid12").value()).endsWith("/old");
        assertThat(resolve.resolve("valid12").value()).endsWith("/old");
        assertThat(repo.reads).hasValue(1);
        new UpdateLinkService(repo, cache, clock).update("valid12", "https://example.org/new");
        assertThat(resolve.resolve("valid12").value()).endsWith("/new");
        assertThatThrownBy(() -> resolve.resolve("absent1"))
                .isInstanceOf(LinkNotFoundException.class);
        int reads = repo.reads.get();
        assertThatThrownBy(() -> resolve.resolve("absent1"))
                .isInstanceOf(LinkNotFoundException.class);
        assertThat(repo.reads).hasValue(reads);
        create.create("https://example.org/created", "absent1");
        assertThat(resolve.resolve("absent1").value()).endsWith("/created");
        assertThatThrownBy(
                        () ->
                                new UpdateLinkService(repo, cache, clock)
                                        .update("unknown", "https://example.org/"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    void oneHundredOverlappingMissesShareOneDatabaseLoad() throws Exception {
        creator(() -> new ShortCode("valid12")).create("https://example.org/", "valid12");
        var resolve = new ResolveLinkService(repo, cache);
        var entered = new CountDownLatch(100);
        var release = new CountDownLatch(1);
        Set<Thread> callers = ConcurrentHashMap.newKeySet();
        cache.onGet =
                () -> {
                    if (callers.add(Thread.currentThread())) {
                        entered.countDown();
                    }
                };
        repo.onRead =
                () -> {
                    try {
                        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CyclicBarrier(101);
            var futures = new ArrayList<Future<TargetUrl>>();
            for (int i = 0; i < 100; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return resolve.resolve("valid12");
                                }));
            }
            start.await(10, TimeUnit.SECONDS);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).value())
                        .isEqualTo("https://example.org/");
            }
        }
        assertThat(repo.reads).hasValue(1);
    }

    @Test
    void failedFlightIsRemovedAndDifferentCodesAreIndependent() throws Exception {
        var resolve = new ResolveLinkService(repo, cache);
        repo.failure = new StorageUnavailableException();
        assertThatThrownBy(() -> resolve.resolve("valid12"))
                .isInstanceOf(StorageUnavailableException.class);
        repo.failure = null;
        creator(() -> new ShortCode("valid12")).create("https://example.org/", "valid12");
        assertThat(resolve.resolve("valid12").value()).isEqualTo("https://example.org/");
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        cache.values.clear();
        repo.onRead =
                () -> {
                    if (Thread.currentThread().getName().equals("blocked-loader")) {
                        blocked.countDown();
                        try {
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                Thread.currentThread().setName("blocked-loader");
                                return resolve.resolve("valid12");
                            });
            assertThat(blocked.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> resolve.resolve("another"))
                        .isInstanceOf(LinkNotFoundException.class);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
    }

    static class FakeRepository implements LinkRepository {
        final Map<ShortCode, Link> data = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        RuntimeException failure;
        Runnable onRead = () -> {};

        public Link insert(Link link) {
            if (failure != null) {
                throw failure;
            }
            if (data.putIfAbsent(link.code(), link) != null) {
                throw new CodeConflictException();
            }
            return link;
        }

        public Optional<Link> find(ShortCode code) {
            reads.incrementAndGet();
            onRead.run();
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(data.get(code));
        }

        public Optional<Link> update(ShortCode code, TargetUrl url, Instant now) {
            return Optional.ofNullable(
                    data.computeIfPresent(code, (k, v) -> new Link(k, url, v.createdAt(), now)));
        }
    }

    static class FakeCache implements LinkCache {
        final Map<ShortCode, CacheResult> values = new ConcurrentHashMap<>();
        Runnable onGet = () -> {};

        public CacheResult get(ShortCode code) {
            onGet.run();
            return values.getOrDefault(code, CacheResult.miss());
        }

        public void put(ShortCode code, TargetUrl url) {
            values.put(code, CacheResult.hit(url));
        }

        public void putMissing(ShortCode code) {
            values.put(code, CacheResult.missing());
        }

        public void evict(ShortCode code) {
            values.remove(code);
        }
    }
}
