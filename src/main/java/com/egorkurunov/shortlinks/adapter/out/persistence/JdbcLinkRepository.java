package com.egorkurunov.shortlinks.adapter.out.persistence;

import com.egorkurunov.shortlinks.application.port.out.LinkRepository;
import com.egorkurunov.shortlinks.domain.error.CodeConflictException;
import com.egorkurunov.shortlinks.domain.error.StorageUnavailableException;
import com.egorkurunov.shortlinks.domain.model.Link;
import com.egorkurunov.shortlinks.domain.model.ShortCode;
import com.egorkurunov.shortlinks.domain.model.TargetUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcLinkRepository implements LinkRepository {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String CODE_CONSTRAINT = "links_pkey";
    private final JdbcTemplate jdbc;

    @Override
    public Link insert(Link link) {
        try {
            jdbc.update(
                    """
                            INSERT INTO links(code, target_url, created_at, updated_at)
                            VALUES (?,?,?,?)
                            """,
                    link.code().value(),
                    link.url().value(),
                    Timestamp.from(link.createdAt()),
                    Timestamp.from(link.updatedAt()));
            return link;
        } catch (DuplicateKeyException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())
                    && constraintIsCode(sql)) {
                throw new CodeConflictException();
            }
            throw e;
        } catch (DataAccessResourceFailureException
                | QueryTimeoutException
                | TransientDataAccessResourceException e) {
            throw new StorageUnavailableException(e);
        }
    }

    private boolean constraintIsCode(SQLException sql) {
        if (sql instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
            return CODE_CONSTRAINT.equals(pg.getServerErrorMessage().getConstraint());
        }
        return false;
    }

    @Override
    public Optional<Link> find(ShortCode code) {
        try {
            return jdbc
                    .query(
                            """
                            SELECT code, target_url, created_at, updated_at
                            FROM links
                            WHERE code=?
                            """,
                            this::map,
                            code.value())
                    .stream()
                    .findFirst();
        } catch (DataAccessResourceFailureException
                | QueryTimeoutException
                | TransientDataAccessResourceException e) {
            throw new StorageUnavailableException(e);
        }
    }

    @Override
    public Optional<Link> update(ShortCode code, TargetUrl url, Instant now) {
        try {
            return jdbc
                    .query(
                            """
                            UPDATE links
                            SET target_url=?, updated_at=?
                            WHERE code=?
                            RETURNING code, target_url, created_at, updated_at
                            """,
                            this::map,
                            url.value(),
                            Timestamp.from(now),
                            code.value())
                    .stream()
                    .findFirst();
        } catch (DataAccessResourceFailureException
                | QueryTimeoutException
                | TransientDataAccessResourceException e) {
            throw new StorageUnavailableException(e);
        }
    }

    private Link map(ResultSet row, int index) throws SQLException {
        return new Link(
                new ShortCode(row.getString("code")),
                new TargetUrl(row.getString("target_url")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
