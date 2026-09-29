package com.martecyber.plugins.caido;

import com.martecyber.ares.plugins.PluginComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Hand-written {@code JdbcTemplate} repository over {@code ares.caido_api_integration} — no
 *  JPA/Hibernate (see {@link CaidoApiIntegration}'s own doc for why). Registered as a {@link
 *  PluginComponent} so {@link CaidoApiPluginLifecycle} can create the table before anything here
 *  is ever queried. */
public class CaidoApiIntegrationRepository implements PluginComponent {

    private final JdbcTemplate jdbc;

    public CaidoApiIntegrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CaidoApiIntegration> ROW_MAPPER = (rs, rowNum) -> {
        CaidoApiIntegration i = new CaidoApiIntegration();
        i.setId(rs.getLong("id"));
        i.setLabel(rs.getString("label"));
        i.setBaseUrl(rs.getString("base_url"));
        i.setAccessTokenCiphertext(rs.getBytes("access_token_ciphertext"));
        i.setAccessTokenIv(rs.getBytes("access_token_iv"));
        i.setEnabled(rs.getBoolean("enabled"));
        i.setConnectionStatus(rs.getString("connection_status"));
        i.setConnectionError(rs.getString("connection_error"));
        i.setLastTestedAt(toOffsetDateTime(rs.getTimestamp("last_tested_at")));
        i.setKnownProjects(rs.getString("known_projects"));
        i.setCreatedAt(toOffsetDateTime(rs.getTimestamp("created_at")));
        i.setUpdatedAt(toOffsetDateTime(rs.getTimestamp("updated_at")));
        return i;
    };

    public List<CaidoApiIntegration> findAllByOrderByLabelAsc() {
        return jdbc.query("SELECT * FROM ares.caido_api_integration ORDER BY label ASC", ROW_MAPPER);
    }

    public Optional<CaidoApiIntegration> findById(Long id) {
        return jdbc.query("SELECT * FROM ares.caido_api_integration WHERE id = ?", ROW_MAPPER, id)
            .stream().findFirst();
    }

    public boolean existsById(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ares.caido_api_integration WHERE id = ?", Integer.class, id) > 0;
    }

    public CaidoApiIntegration save(CaidoApiIntegration i) {
        if (i.getId() == null) {
            Long id = jdbc.queryForObject("""
                INSERT INTO ares.caido_api_integration
                    (label, base_url, access_token_ciphertext, access_token_iv, enabled,
                     connection_status, connection_error, last_tested_at, known_projects, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class,
                i.getLabel(), i.getBaseUrl(), i.getAccessTokenCiphertext(), i.getAccessTokenIv(), i.isEnabled(),
                i.getConnectionStatus(), i.getConnectionError(), toTimestamp(i.getLastTestedAt()),
                i.getKnownProjects(), toTimestamp(i.getCreatedAt()), toTimestamp(i.getUpdatedAt()));
            i.setId(id);
        } else {
            jdbc.update("""
                UPDATE ares.caido_api_integration SET
                    label = ?, base_url = ?, access_token_ciphertext = ?, access_token_iv = ?, enabled = ?,
                    connection_status = ?, connection_error = ?, last_tested_at = ?, known_projects = ?::jsonb, updated_at = ?
                WHERE id = ?
                """,
                i.getLabel(), i.getBaseUrl(), i.getAccessTokenCiphertext(), i.getAccessTokenIv(), i.isEnabled(),
                i.getConnectionStatus(), i.getConnectionError(), toTimestamp(i.getLastTestedAt()),
                i.getKnownProjects(), toTimestamp(i.getUpdatedAt()), i.getId());
        }
        return i;
    }

    public void delete(CaidoApiIntegration i) {
        jdbc.update("DELETE FROM ares.caido_api_integration WHERE id = ?", i.getId());
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime dt) {
        return dt == null ? null : Timestamp.from(dt.toInstant());
    }
}
