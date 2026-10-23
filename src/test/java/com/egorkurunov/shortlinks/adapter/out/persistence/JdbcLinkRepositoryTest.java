package com.egorkurunov.shortlinks.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

class JdbcLinkRepositoryTest {
    @Test
    void unavailableConnectionsPreserveTheDiagnosticCauseForAllOperations() {
        var dataSource =
                new AbstractDataSource() {
                    @Override
                    public Connection getConnection() throws SQLException {
                        throw new SQLException("Connection unavailable", "08006");
                    }

                    @Override
                    public Connection getConnection(String username, String password)
                            throws SQLException {
                        return getConnection();
                    }
                };
        var repository = new JdbcLinkRepository(new JdbcTemplate(dataSource));
        var code = new ShortCode("abc1234");
        var url = new TargetUrl("https://example.org/");
        var now = Instant.now();
        for (ThrowingCallable operation :
                new ThrowingCallable[] {
                    () -> repository.find(code),
                    () -> repository.insert(new Link(code, url, now, now)),
                    () -> repository.update(code, url, now)
                }) {
            assertThatThrownBy(operation)
                    .isInstanceOf(StorageUnavailableException.class)
                    .hasRootCauseInstanceOf(SQLException.class);
        }
    }
}
