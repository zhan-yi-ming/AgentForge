package com.agentforge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.RollbackException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.agent.application.AgentActionService;
import com.agentforge.core.agent.application.AgentActionView;
import com.agentforge.core.agent.application.ToolProposal;
import com.agentforge.core.agent.application.AiUsageQuota;
import com.agentforge.core.shared.error.RateLimitExceededException;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.wiki.domain.WikiPage;
import com.agentforge.core.conversation.application.ConversationHistoryService;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
            "agentforge.security.jwt.ttl=PT30M",
            "agentforge.agent-service.internal-token=test-only-internal-token",
            "agentforge.core-internal.token=test-only-core-token",
            "agentforge.ai.daily-limit=2"
        })
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private AgentActionService agentActionService;

    @Autowired
    private AiUsageQuota aiUsageQuota;

    @Autowired
    private ConversationHistoryService conversationHistoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void conversationHistoryPersistsAndReadsOnlyItsBoundScope() {
        var authentication = authenticationService.register(
                "history-integration@example.com", "History Integration", "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "History Project", null);
        var conversationId = java.util.UUID.randomUUID();

        conversationHistoryService.appendCompletedExchange(
                project.id(), actor, conversationId, "What changed?", "RBAC changed.", java.util.List.of());

        assertThat(conversationHistoryService.list(project.id(), actor)).hasSize(1);
        var detail = conversationHistoryService.get(project.id(), conversationId, actor);
        assertThat(detail.messages()).extracting(message -> message.content())
                .containsExactly("What changed?", "RBAC changed.");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_message where conversation_id = ?", Integer.class, conversationId))
                .isEqualTo(2);
    }

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
                Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'",
                Integer.class)).isEqualTo(1);
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

    @Test
    void pendingAgentActionWritesTaskOnlyOnceAfterConfirmation() {
        var authentication = authenticationService.register(
                "agent-action@example.com",
                "Agent Action",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "Agent Action Project", null);
        var proposal = new ToolProposal(
                "CREATE_TASK", null, null, "Confirm me", "Created after confirmation", "TODO", "HIGH");

        var pending = agentActionService.createPending(project.id(), actor, java.util.UUID.randomUUID(), proposal)
                .orElseThrow();

        assertThat(taskService.list(project.id(), actor)).isEmpty();
        var executed = confirmDirectly(
                project.id(), pending.id(), actor, "integration-confirm-key", "integration-request-1");
        var repeated = confirmDirectly(
                project.id(), pending.id(), actor, "integration-confirm-key", "integration-request-2");

        assertThat(executed.resultTask().id()).isEqualTo(repeated.resultTask().id());
        assertThat(taskService.list(project.id(), actor)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from agent_task_action where id = ?",
                String.class,
                pending.id())).isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                "select idempotency_key from agent_task_action where id = ?",
                String.class,
                pending.id())).isEqualTo("integration-confirm-key");
        assertThat(jdbcTemplate.queryForList(
                "select event_type from agent_action_audit_event where approval_id = ? order by created_at, id",
                String.class,
                pending.id())).containsExactlyInAnyOrder("REQUESTED", "APPROVED", "EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true",
                Integer.class)).isGreaterThanOrEqualTo(7);
    }

    @Test
    void approvedActionPersistsFailedStateWhenTheTargetVersionChanged() {
        var authentication = authenticationService.register(
                "agent-action-failure@example.com", "Agent Action Failure", "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "Failed Approval Project", null);
        var task = taskService.create(project.id(), actor, "Original", null, null, null);
        var proposal = new ToolProposal(
                "UPDATE_TASK", task.id(), task.version(), "Approved title", null, null, null);
        var pending = agentActionService.createPending(
                project.id(), actor, java.util.UUID.randomUUID(), proposal, "failure-requested")
                .orElseThrow();
        taskService.update(
                project.id(), task.id(), actor, "Changed elsewhere", null,
                task.status(), task.priority(), task.version());

        var failed = confirmDirectly(
                project.id(), pending.id(), actor, "failure-key", "failure-confirm");
        var replayed = confirmDirectly(
                project.id(), pending.id(), actor, "failure-key", "failure-replay");

        assertThat(failed.status()).isEqualTo(com.agentforge.core.agent.domain.AgentActionStatus.FAILED);
        assertThat(replayed.status()).isEqualTo(com.agentforge.core.agent.domain.AgentActionStatus.FAILED);
        assertThat(taskService.get(project.id(), task.id(), actor).title()).isEqualTo("Changed elsewhere");
        assertThat(jdbcTemplate.queryForObject(
                "select status from agent_task_action where id = ?", String.class, pending.id()))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForList(
                "select event_type from agent_action_audit_event where approval_id = ? order by created_at, id",
                String.class, pending.id()))
                .containsExactlyInAnyOrder("REQUESTED", "APPROVED", "FAILED");
    }

    @Test
    void concurrentConfirmationCreatesOneTaskAndOneExecutionAuditEvent() throws Exception {
        var authentication = authenticationService.register(
                "agent-action-concurrent@example.com", "Concurrent Approval", "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "Concurrent Approval Project", null);
        var pending = agentActionService.createPending(
                project.id(), actor, java.util.UUID.randomUUID(),
                new ToolProposal("CREATE_TASK", null, null, "Create once", null, "TODO", "MEDIUM"),
                "concurrent-requested").orElseThrow();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return confirmDirectly(
                        project.id(), pending.id(), actor, "concurrent-key", "concurrent-1");
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return confirmDirectly(
                        project.id(), pending.id(), actor, "concurrent-key", "concurrent-2");
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).resultTask().id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).resultTask().id());
        }

        assertThat(taskService.list(project.id(), actor)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_action_audit_event where approval_id = ? and event_type = 'EXECUTED'",
                Integer.class, pending.id())).isEqualTo(1);
    }

    @Test
    void aiUsageQuotaPersistsAndRejectsTheFirstRequestAboveTheLimit() {
        var authentication = authenticationService.register(
                "quota-integration@example.com",
                "Quota Integration",
                "integration-password");
        var userId = authentication.user().id();

        aiUsageQuota.consume(userId);
        aiUsageQuota.consume(userId);

        assertThatThrownBy(() -> aiUsageQuota.consume(userId))
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select request_count from ai_usage_daily where user_id = ?",
                Integer.class,
                userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select usage_date from ai_usage_daily where user_id = ?",
                LocalDate.class,
                userId)).isEqualTo(LocalDate.now(Clock.systemUTC()));
    }

    private AgentActionView confirmDirectly(
            java.util.UUID projectId,
            java.util.UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        AgentActionView approved = agentActionService.approve(
                projectId, actionId, actor, idempotencyKey, requestId);
        if (approved.status()
                != com.agentforge.core.agent.domain.AgentActionStatus.APPROVED) {
            return approved;
        }
        return agentActionService.executeApproved(
                projectId, actionId, actor, idempotencyKey, requestId);
    }
}
