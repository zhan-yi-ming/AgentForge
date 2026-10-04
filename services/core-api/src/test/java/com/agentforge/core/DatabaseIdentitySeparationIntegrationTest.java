package com.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DatabaseIdentitySeparationIntegrationTest {

    private static final String CORE_PASSWORD = "core_test_password_123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE ROLE agentforge_core LOGIN PASSWORD 'core_test_password_123'
                        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
                    """);
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "agentforge_core");
        registry.add("spring.datasource.password", () -> CORE_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("agentforge.security.jwt.secret",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("agentforge.security.jwt.issuer", () -> "https://agentforge.test/core-api");
        registry.add("agentforge.agent-service.internal-token", () -> "test-only-internal-token");
        registry.add("agentforge.core-internal.token", () -> "test-only-core-token");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayUsesAdminWhileRuntimeDatasourceRemainsRestrictedCoreRole() {
        assertThat(jdbcTemplate.queryForObject("select current_user", String.class))
                .isEqualTo("agentforge_core");
        assertThat(jdbcTemplate.queryForObject(
                "select max(version::integer) from flyway_schema_history where success", Integer.class))
                .isEqualTo(18);
        assertThat(jdbcTemplate.queryForObject(
                "select tableowner from pg_tables where schemaname='public' and tablename='app_user'",
                String.class)).isEqualTo(POSTGRES.getUsername());
        assertThat(jdbcTemplate.queryForObject(
                "select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select has_table_privilege(current_user, 'public.app_user', 'SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class)).isTrue();
    }

    @Test
    void ragSourceGenerationAdvancesOnlyWhenProjectSourcesChange() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID wikiId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO app_user(id,email,display_name,created_at,updated_at)
                VALUES (?,?,'RAG Owner',now(),now())
                """, userId, userId + "@example.test");
        jdbcTemplate.update("""
                INSERT INTO project(id,owner_id,name,created_at,updated_at)
                VALUES (?,?,'RAG Generation',now(),now())
                """, projectId, userId);

        assertThat(generation(projectId)).isZero();
        assertThat(generation(projectId)).isZero();
        jdbcTemplate.update("""
                INSERT INTO wiki_page(id,project_id,title,content,created_at,updated_at)
                VALUES (?,?,'Snapshot','first',now(),now())
                """, wikiId, projectId);
        assertThat(generation(projectId)).isEqualTo(1);
        jdbcTemplate.update("UPDATE wiki_page SET content='second',version=version+1 WHERE id=?", wikiId);
        assertThat(generation(projectId)).isEqualTo(2);
        jdbcTemplate.update("DELETE FROM wiki_page WHERE id=?", wikiId);
        assertThat(generation(projectId)).isEqualTo(3);
    }

    private long generation(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT generation FROM rag_source_generation WHERE project_id=?",
                Long.class,
                projectId);
    }
}
