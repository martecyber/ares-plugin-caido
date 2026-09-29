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

/** Hand-written {@code JdbcTemplate} repository over {@code ares.caido_api_task} — no
 *  JPA/Hibernate (see {@link CaidoApiIntegration}'s own doc for why). */
public class CaidoApiTaskRepository implements PluginComponent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CaidoApiTaskRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private final RowMapper<CaidoApiTask> rowMapper = (rs, rowNum) -> {
        CaidoApiTask t = new CaidoApiTask();
        t.setId(rs.getLong("id"));
        t.setIntegrationId(rs.getLong("integration_id"));
        t.setProjectId(rs.getLong("project_id"));
        t.setCaidoProjectId(rs.getString("caido_project_id"));
        t.setCaidoProjectName(rs.getString("caido_project_name"));
        t.setAction(rs.getString("action"));
        t.setCronExpression(rs.getString("cron_expression"));
        t.setEnabled(rs.getBoolean("enabled"));
        t.setStatus(rs.getString("status"));
        t.setResult(rs.getString("result"));
        t.setError(rs.getString("error"));
        t.setCreatedAt(toOffsetDateTime(rs.getTimestamp("created_at")));
        t.setLastRunAt(toOffsetDateTime(rs.getTimestamp("last_run_at")));
        t.setNextRunAt(toOffsetDateTime(rs.getTimestamp("next_run_at")));
        t.setExcludedScopeValues(readStringList(rs.getString("excluded_scope_values")));
        return t;
    };

    public List<CaidoApiTask> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return jdbc.query("SELECT * FROM ares.caido_api_task WHERE project_id = ? ORDER BY created_at DESC", rowMapper, projectId);
    }

    /** Mirrors the original JPQL: enabled, not currently running, and either a one-shot task
     *  still pending or a recurring one whose next run is due. */
    public List<CaidoApiTask> findAllDue(OffsetDateTime now) {
        return jdbc.query("""
            SELECT * FROM ares.caido_api_task
            WHERE enabled = true AND status <> 'running'
              AND ( (cron_expression IS NULL AND status = 'pending')
                    OR (cron_expression IS NOT NULL AND (next_run_at IS NULL OR next_run_at <= ?)) )
            """, rowMapper, toTimestamp(now));
    }

    public Optional<CaidoApiTask> findById(Long id) {
        return jdbc.query("SELECT * FROM ares.caido_api_task WHERE id = ?", rowMapper, id).stream().findFirst();
    }

    public CaidoApiTask save(CaidoApiTask t) {
        String excluded = writeStringList(t.getExcludedScopeValues());
        if (t.getId() == null) {
            Long id = jdbc.queryForObject("""
                INSERT INTO ares.caido_api_task
                    (integration_id, project_id, caido_project_id, caido_project_name, action, cron_expression,
                     enabled, status, result, error, created_at, last_run_at, next_run_at, excluded_scope_values)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """, Long.class,
                t.getIntegrationId(), t.getProjectId(), t.getCaidoProjectId(), t.getCaidoProjectName(),
                t.getAction(), t.getCronExpression(), t.isEnabled(), t.getStatus(), t.getResult(), t.getError(),
                toTimestamp(t.getCreatedAt()), toTimestamp(t.getLastRunAt()), toTimestamp(t.getNextRunAt()), excluded);
            t.setId(id);
        } else {
            jdbc.update("""
                UPDATE ares.caido_api_task SET
                    integration_id = ?, project_id = ?, caido_project_id = ?, caido_project_name = ?, action = ?,
                    cron_expression = ?, enabled = ?, status = ?, result = ?::jsonb, error = ?,
                    last_run_at = ?, next_run_at = ?, excluded_scope_values = ?::jsonb
                WHERE id = ?
                """,
                t.getIntegrationId(), t.getProjectId(), t.getCaidoProjectId(), t.getCaidoProjectName(),
                t.getAction(), t.getCronExpression(), t.isEnabled(), t.getStatus(), t.getResult(), t.getError(),
                toTimestamp(t.getLastRunAt()), toTimestamp(t.getNextRunAt()), excluded, t.getId());
        }
        return t;
    }

    public void delete(CaidoApiTask t) {
        jdbc.update("DELETE FROM ares.caido_api_task WHERE id = ?", t.getId());
    }

    private List<String> readStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return null;
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private String writeStringList(List<String> values) {
        if (values == null) return null;
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime dt) {
        return dt == null ? null : Timestamp.from(dt.toInstant());
    }
}
