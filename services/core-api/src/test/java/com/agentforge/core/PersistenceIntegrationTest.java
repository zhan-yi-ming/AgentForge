package com.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.user.application.UserService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private UserService userService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesSchemaAndJpaPersistsUserAndProject() {
        var user = userService.createUser("integration@example.com", "Integration User");
        var project = projectService.createProject(user.id(), "Integration Project", null);

        assertThat(projectService.getProject(project.id()).ownerId()).isEqualTo(user.id());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true",
                Integer.class)).isPositive();
    }
}
