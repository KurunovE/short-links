package com.egorkurunov.shortlinks.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import com.github.dockerjava.api.model.ExposedPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcLinkRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16.13-alpine");

    JdbcTemplate jdbc;
    JdbcLinkRepository repo;

    @BeforeEach
    void setup() {
        var ds = currentDataSource();
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE links");
        repo = new JdbcLinkRepository(jdbc);
    }

    private DriverManagerDataSource currentDataSource() {
        // Raw Docker restarts can change random port bindings; Testcontainers caches the old ones.
        var info = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec();
        String port =
                info.getNetworkSettings()
                        .getPorts()
                        .getBindings()
                        .get(ExposedPort.tcp(5432))[0]
                        .getHostPortSpec();
        return new DriverManagerDataSource(
                "jdbc:postgresql://%s:%s/%s?connectTimeout=3&socketTimeout=5"
                        .formatted(postgres.getHost(), port, postgres.getDatabaseName()),
                postgres.getUsername(),
                postgres.getPassword());
    }

    Link link(String code) {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        return new Link(new ShortCode(code), new TargetUrl("https://example.org/old"), now, now);
    }

    @Test
    void persistsFindsAndUpdatesAtomically() {
        var original = link("abc1234");
        repo.insert(original);
        assertThat(repo.find(original.code())).contains(original);
        var now = original.createdAt().plusSeconds(60);
        var changed =
                repo.update(original.code(), new TargetUrl("https://example.org/new"), now)
                        .orElseThrow();
        assertThat(changed.url().value()).endsWith("/new");
        assertThat(changed.createdAt()).isEqualTo(original.createdAt());
        assertThat(changed.updatedAt()).isEqualTo(now);
        assertThat(repo.update(new ShortCode("missing"), changed.url(), now)).isEmpty();
        assertThat(repo.find(new ShortCode("missing"))).isEmpty();
    }

    @Test
    void concurrentInsertsHaveOneWinner() throws Exception {
        var barrier = new CyclicBarrier(21);
        int successes = 0, conflicts = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 20; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    try {
                                        repo.insert(link("same123"));
                                        return true;
                                    } catch (CodeConflictException e) {
                                        return false;
                                    }
                                }));
            }
            barrier.await(10, TimeUnit.SECONDS);
            for (var future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) {
                    successes++;
                } else {
                    conflicts++;
                }
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(19);
    }

    @Test
    void databaseOutageIsAnAvailabilityFailureForEveryOperation() {
        Link preserved = link("preserved");
        repo.insert(preserved);
        var docker = postgres.getDockerClient();
        docker.stopContainerCmd(postgres.getContainerId()).withTimeout(1).exec();
        try {
            assertThatThrownBy(() -> repo.find(new ShortCode("abc1234")))
                    .isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(() -> repo.insert(link("abc1234")))
                    .isInstanceOf(StorageUnavailableException.class);
            assertThatThrownBy(
                            () ->
                                    repo.update(
                                            new ShortCode("abc1234"),
                                            new TargetUrl("https://example.org/new"),
                                            Instant.now()))
                    .isInstanceOf(StorageUnavailableException.class);
        } finally {
            docker.startContainerCmd(postgres.getContainerId()).exec();
            var ds = currentDataSource();
            jdbc = new JdbcTemplate(ds);
            repo = new JdbcLinkRepository(jdbc);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .ignoreExceptionsInstanceOf(CannotGetJdbcConnectionException.class)
                    .untilAsserted(
                            () ->
                                    assertThat(jdbc.queryForObject("SELECT 1", Integer.class))
                                            .isEqualTo(1));
            assertThat(repo.find(preserved.code())).contains(preserved);
        }
    }

    @Test
    void unrelatedIntegrityFailureIsNotAnAliasConflict() {
        jdbc.execute(
                "ALTER TABLE links ADD CONSTRAINT test_target CHECK (target_url <> 'https://example.org/old')");
        try {
            assertThatThrownBy(() -> repo.insert(link("abc1234")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(CodeConflictException.class);
        } finally {
            jdbc.execute("ALTER TABLE links DROP CONSTRAINT test_target");
        }
    }
}
