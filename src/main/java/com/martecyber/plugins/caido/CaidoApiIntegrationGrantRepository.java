package com.martecyber.plugins.caido;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.plugins.PluginComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Hand-written {@code JdbcTemplate} repository over {@code ares.caido_api_integration_grant} —
 *  no JPA/Hibernate (see {@link CaidoApiIntegration}'s own doc for why). */
public class CaidoApiIntegrationGrantRepository implements PluginComponent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CaidoApiIntegrationGrantRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private final RowMapper<CaidoApiIntegrationGrant> rowMapper = (rs, rowNum) -> {
        CaidoApiIntegrationGrant g = new CaidoApiIntegrationGrant();
        g.setId(rs.getLong("id"));
        g.setIntegrationId(rs.getLong("integration_id"));
        g.setOrganizationId(rs.getLong("organization_id"));
        long projectId = rs.getLong("project_id");
        g.setProjectId(rs.wasNull() ? null : projectId);
        g.setCapabilities(readStringList(rs.getString("capabilities")));
        g.setActive(rs.getBoolean("active"));
        g.setCreatedAt(toOffsetDateTime(rs.getTimestamp("created_at")));
        g.setUpdatedAt(toOffsetDateTime(rs.getTimestamp("updated_at")));
        return g;
    };

    public List<CaidoApiIntegrationGrant> findByIntegrationId(Long integrationId) {
        return jdbc.query("SELECT * FROM ares.caido_api_integration_grant WHERE integration_id = ?", rowMapper, integrationId);
    }

    /** Mirrors the original JPQL: active grants visible to this org/project (org-wide when
     *  {@code project_id IS NULL}, otherwise scoped to that exact project). */
    public List<CaidoApiIntegrationGrant> findVisibleToProject(Long orgId, Long projectId) {
        return jdbc.query("""
            SELECT * FROM ares.caido_api_integration_grant
            WHERE active = true AND organization_id = ? AND (project_id IS NULL OR project_id = ?)
            """, rowMapper, orgId, projectId);
    }

    public Optional<CaidoApiIntegrationGrant> findById(Long id) {
        return jdbc.query("SELECT * FROM ares.caido_api_integration_grant WHERE id = ?", rowMapper, id).stream().findFirst();
    }

    public CaidoApiIntegrationGrant save(CaidoApiIntegrationGrant g) {
        String capabilities = writeStringList(g.getCapabilities());
        if (g.getId() == null) {
            Long id = jdbc.queryForObject("""
                INSERT INTO ares.caido_api_integration_grant
                    (integration_id, organization_id, project_id, capabilities, active, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class,
                g.getIntegrationId(), g.getOrganizationId(), g.getProjectId(), capabilities, g.isActive(),
                toTimestamp(g.getCreatedAt()), toTimestamp(g.getUpdatedAt()));
            g.setId(id);
        } else {
            jdbc.update("""
                UPDATE ares.caido_api_integration_grant SET
                    integration_id = ?, organization_id = ?, project_id = ?, capabilities = ?::jsonb,
                    active = ?, updated_at = ?
                WHERE id = ?
                """,
                g.getIntegrationId(), g.getOrganizationId(), g.getProjectId(), capabilities, g.isActive(),
                toTimestamp(g.getUpdatedAt()), g.getId());
        }
        return g;
    }

    public void delete(CaidoApiIntegrationGrant g) {
        jdbc.update("DELETE FROM ares.caido_api_integration_grant WHERE id = ?", g.getId());
    }

    private List<String> readStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return List.of();
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeStringList(List<String> values) {
        try {
            return json.writeValueAsString(values != null ? values : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime dt) {
        return dt == null ? null : Timestamp.from(dt.toInstant());
    }
}
