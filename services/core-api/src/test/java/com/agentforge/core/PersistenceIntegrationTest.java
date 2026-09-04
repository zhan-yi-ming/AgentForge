package com.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.wiki.domain.WikiPage;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
            "agentforge.security.jwt.ttl=PT30M",
            "agentforge.agent-service.internal-token=test-only-internal-token"
        })
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void flywayCreatesSchemaAndJpaPersistsAuthenticatedProjectResources() {
        var authentication = authenticationService.register(
                "integration@example.com",
                "Integration User",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "Integration Project", null);
        var wikiPage = wikiPageService.create(project.id(), actor, "Architecture", "# Core API");
        var task = taskService.create(project.id(), actor, "Verify migration", null, null, null);

        assertThat(projectService.getProject(project.id(), actor).ownerId())
                .isEqualTo(authentication.user().id());
        assertThat(wikiPageService.get(project.id(), wikiPage.id(), actor).title())
                .isEqualTo("Architecture");
        assertThat(taskService.get(project.id(), task.id(), actor).title())
                .isEqualTo("Verify migration");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true",
                Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void wikiVersionRejectsASecondCommitFromAStaleSnapshot() {
        var authentication = authenticationService.register(
                "optimistic-lock@example.com",
                "Lock Test",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "Lock Project", null);
        var created = wikiPageService.create(project.id(), actor, "Concurrency", "initial");
        var firstManager = entityManagerFactory.createEntityManager();
        var secondManager = entityManagerFactory.createEntityManager();
        try {
            firstManager.getTransaction().begin();
            secondManager.getTransaction().begin();
            WikiPage first = firstManager.find(WikiPage.class, created.id());
            WikiPage stale = secondManager.find(WikiPage.class, created.id());
            first.update("Concurrency", "first", Instant.now());
            stale.update("Concurrency", "stale", Instant.now());
            firstManager.getTransaction().commit();

            assertThatThrownBy(secondManager.getTransaction()::commit)
                    .isInstanceOf(RollbackException.class);
        }
        finally {
            if (firstManager.getTransaction().isActive()) {
                firstManager.getTransaction().rollback();
            }
            if (secondManager.getTransaction().isActive()) {
                secondManager.getTransaction().rollback();
            }
            firstManager.close();
            secondManager.close();
        }
    }
}
