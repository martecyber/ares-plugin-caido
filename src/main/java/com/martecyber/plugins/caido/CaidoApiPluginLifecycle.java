package com.martecyber.plugins.caido;

import com.martecyber.ares.plugins.PluginLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates this plugin's 3 owned tables (raw SQL — see {@link CaidoApiIntegration}'s own doc for
 * why not JPA) on install. These tables previously lived in ares-core, created by that module's
 * own {@code V94}/{@code V95}/{@code V102} Flyway migrations — those migrations are immutable
 * history and stay in ares-core forever (Flyway migrations already applied on a real instance can
 * never be un-applied), so on any pre-existing instance this is a true no-op against tables that
 * already exist with all their data intact. Only a hypothetical brand-new ares-core instance that
 * somehow never ran those migrations would actually need this to create them fresh.
 *
 * <p>{@link #onForget} deliberately does nothing — an uninstall-then-reinstall never loses a
 * configured integration/task's history, same precedent {@code BugHuntingPluginLifecycle} sets.
 */
public class CaidoApiPluginLifecycle implements PluginLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CaidoApiPluginLifecycle.class);

    @Override
    public void onInstall(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ares.caido_api_integration (
                id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                label                    VARCHAR(120) NOT NULL UNIQUE,
                base_url                 VARCHAR(500) NOT NULL,
                access_token_ciphertext  BYTEA,
                access_token_iv          BYTEA,
                enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
                connection_status        VARCHAR(32)  NOT NULL DEFAULT 'unknown',
                connection_error         TEXT,
                last_tested_at           TIMESTAMPTZ,
                known_projects           JSONB        NOT NULL DEFAULT '[]'::jsonb,
                created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
            )
            """);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ares.caido_api_integration_grant (
                id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                integration_id   BIGINT      NOT NULL REFERENCES ares.caido_api_integration(id) ON DELETE CASCADE,
                organization_id  BIGINT      NOT NULL,
                project_id       BIGINT      REFERENCES ares.project(id) ON DELETE CASCADE,
                capabilities     JSONB       NOT NULL DEFAULT '[]'::jsonb,
                active           BOOLEAN     NOT NULL DEFAULT TRUE,
                created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_caido_api_grant_integration ON ares.caido_api_integration_grant(integration_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_caido_api_grant_project ON ares.caido_api_integration_grant(project_id)");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ares.caido_api_task (
                id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                integration_id     BIGINT       NOT NULL REFERENCES ares.caido_api_integration(id) ON DELETE CASCADE,
                project_id         BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
                caido_project_id   VARCHAR(255) NOT NULL,
                caido_project_name VARCHAR(255),
                action             VARCHAR(30)  NOT NULL,
                cron_expression    VARCHAR(120),
                enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
                status             VARCHAR(20)  NOT NULL DEFAULT 'pending',
                result             JSONB,
                error              TEXT,
                created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                last_run_at        TIMESTAMPTZ,
                next_run_at        TIMESTAMPTZ,
                excluded_scope_values JSONB
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_caido_api_task_project ON ares.caido_api_task(project_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_caido_api_task_integration ON ares.caido_api_task(integration_id)");

        log.info("ares-plugin-caido: tables ready");
    }

    @Override
    public void onForget(JdbcTemplate jdbc) {
        log.info("ares-plugin-caido: uninstalled (tables/data left in place)");
    }
}
