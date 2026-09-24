package com.agentforge.core.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agentforge.security.jwt.secret="
                    + "MDEyMzQ1Njc4OWFiY2Rl"
                    + "ZjAxMjM0NTY3ODlhYmNkZWY=",
            "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
            "agentforge.security.jwt.ttl=PT30M",
            "agentforge.agent-service.internal-token=test-only-internal-token",
            "agentforge.core-internal.token=test-only-core-token"
        })
class McpHttpApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @LocalServerPort
    private int port;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void authenticatedClientCanInitializeAndDiscoverOnlyTheFourV301Tools() throws Exception {
        var authentication = authenticationService.register(
                "mcp-" + UUID.randomUUID() + "@example.com",
                "MCP Contract",
                "integration-password");
        String token = authentication.token().value();

        JsonNode initialized = post(token, """
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-11-25",
                    "capabilities":{},
                    "clientInfo":{"name":"agentforge-test","version":"1.0"}
                  }
                }
                """);
        assertThat(initialized.at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");

        JsonNode listed = post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        Set<String> names = new HashSet<>();
        listed.at("/result/tools").forEach(tool -> names.add(tool.path("name").asText()));

        assertThat(names).containsExactlyInAnyOrder(
                "search_wiki", "get_task", "create_task", "update_task");
        listed.at("/result/tools").forEach(tool -> {
            if (tool.path("name").asText().equals("create_task")
                    || tool.path("name").asText().equals("update_task")) {
                assertThat(tool.at("/inputSchema/properties/description/minLength").asInt())
                        .isEqualTo(1);
            }
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        });
    }

    @Test
    void readToolsReuseAuthorizedWikiAndTaskServices() throws Exception {
        var authentication = authenticationService.register(
                "mcp-read-" + UUID.randomUUID() + "@example.com",
                "MCP Reads",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Read Project", null);
        wikiPageService.create(project.id(), actor, "Deployment Runbook", "Deploy with a verified backup.");
        wikiPageService.create(project.id(), actor, "Architecture", "Service boundaries.");
        var task = taskService.create(project.id(), actor, "Verify release", "Run smoke tests",
                TaskStatus.IN_PROGRESS, TaskPriority.HIGH);

        JsonNode wikiResult = callTool(authentication.token().value(), "search_wiki", Map.of(
                "projectId", project.id().toString(),
                "query", "deploy"));
        assertThat(wikiResult.at("/result/isError").asBoolean()).isFalse();
        assertThat(wikiResult.at("/result/structuredContent/items")).hasSize(1);
        assertThat(wikiResult.at("/result/structuredContent/items/0/title").asText())
                .isEqualTo("Deployment Runbook");

        JsonNode taskResult = callTool(authentication.token().value(), "get_task", Map.of(
                "projectId", project.id().toString(),
                "taskId", task.id().toString()));
        assertThat(taskResult.at("/result/isError").asBoolean()).isFalse();
        assertThat(taskResult.at("/result/structuredContent/id").asText()).isEqualTo(task.id().toString());
        assertThat(taskResult.at("/result/structuredContent/status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(taskResult.at("/result/structuredContent/priority").asText()).isEqualTo("HIGH");
    }

    @Test
    void createTaskStaysPendingUntilHttpConfirmationAndExecutesOnlyOnce() throws Exception {
        var authentication = authenticationService.register(
                "mcp-write-" + UUID.randomUUID() + "@example.com",
                "MCP Writes",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Write Project", null);
        String token = authentication.token().value();

        JsonNode proposal = callTool(token, "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-create-once",
                "title", "Ship MCP adapter",
                "description", "Complete V3-01",
                "status", "TODO",
                "priority", "HIGH"));

        assertThat(proposal.at("/result/isError").asBoolean()).isFalse();
        assertThat(proposal.at("/result/structuredContent/status").asText()).isEqualTo("PENDING");
        assertThat(proposal.at("/result/structuredContent/actionType").asText()).isEqualTo("CREATE_TASK");
        assertThat(proposal.at("/result/structuredContent/riskLevel").asText()).isEqualTo("LOW");
        assertThat(taskService.list(project.id(), actor)).isEmpty();

        UUID approvalId = UUID.fromString(
                proposal.at("/result/structuredContent/approvalId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "select source from agent_task_action where id = ?",
                String.class,
                approvalId)).isEqualTo("MCP");
        assertThat(jdbcTemplate.queryForObject(
                "select conversation_id is null and action_workflow_version is null "
                        + "from agent_task_action where id = ?",
                Boolean.class,
                approvalId)).isTrue();

        JsonNode confirmed = confirm(token, project.id(), approvalId, "mcp-create-once");
        assertThat(confirmed.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(confirmed.at("/resultTask/title").asText()).isEqualTo("Ship MCP adapter");
        assertThat(taskService.list(project.id(), actor)).hasSize(1);

        JsonNode replayed = confirm(token, project.id(), approvalId, "mcp-create-once");
        assertThat(replayed.path("status").asText()).isEqualTo("EXECUTED");
        assertThat(replayed.at("/resultTask/id").asText())
                .isEqualTo(confirmed.at("/resultTask/id").asText());
        JsonNode proposalReplay = callTool(token, "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-create-once",
                "title", "Ship MCP adapter",
                "description", "Complete V3-01",
                "status", "TODO",
                "priority", "HIGH"));
        assertThat(proposalReplay.at("/result/structuredContent/approvalId").asText())
                .isEqualTo(approvalId.toString());
        assertThat(proposalReplay.at("/result/structuredContent/status").asText())
                .isEqualTo("EXECUTED");
        assertThat(taskService.list(project.id(), actor)).hasSize(1);
    }

    @Test
    void updateTaskUsesExpectedVersionAndRemainsPendingUntilConfirmation() throws Exception {
        var authentication = authenticationService.register(
                "mcp-update-" + UUID.randomUUID() + "@example.com",
                "MCP Updates",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Update Project", null);
        var task = taskService.create(project.id(), actor, "Draft adapter", "Initial",
                TaskStatus.TODO, TaskPriority.MEDIUM);

        JsonNode proposal = callTool(authentication.token().value(), "update_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-update-once",
                "taskId", task.id().toString(),
                "expectedTaskVersion", task.version(),
                "title", "Review adapter",
                "status", "DONE"));

        assertThat(proposal.at("/result/isError").asBoolean()).isFalse();
        assertThat(proposal.at("/result/structuredContent/status").asText()).isEqualTo("PENDING");
        assertThat(proposal.at("/result/structuredContent/riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(taskService.get(project.id(), task.id(), actor).title()).isEqualTo("Draft adapter");

        UUID approvalId = UUID.fromString(
                proposal.at("/result/structuredContent/approvalId").asText());
        JsonNode confirmed = confirm(
                authentication.token().value(), project.id(), approvalId, "mcp-update-once");

        assertThat(confirmed.path("status").asText()).isEqualTo("EXECUTED");
        var updated = taskService.get(project.id(), task.id(), actor);
        assertThat(updated.title()).isEqualTo("Review adapter");
        assertThat(updated.status()).isEqualTo(TaskStatus.DONE);
        assertThat(updated.version()).isEqualTo(task.version() + 1);
    }

    @Test
    void writeToolProposalRetriesReuseApprovalAndRejectDifferentIntent() throws Exception {
        var authentication = authenticationService.register(
                "mcp-retry-" + UUID.randomUUID() + "@example.com",
                "MCP Retry",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Retry Project", null);
        Map<String, Object> arguments = Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-retry-key",
                "title", "Retry-safe task");

        JsonNode first = callTool(authentication.token().value(), "create_task", arguments);
        JsonNode replay = callTool(authentication.token().value(), "create_task", arguments);

        assertThat(first.at("/result/isError").asBoolean()).isFalse();
        assertThat(replay.at("/result/isError").asBoolean()).isFalse();
        assertThat(replay.at("/result/structuredContent/approvalId").asText())
                .isEqualTo(first.at("/result/structuredContent/approvalId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_task_action where proposal_idempotency_key = ?",
                Integer.class,
                "proposal-retry-key")).isEqualTo(1);

        JsonNode conflicting = callTool(authentication.token().value(), "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-retry-key",
                "title", "Different intent"));
        assertThat(conflicting.at("/result/isError").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_task_action where proposal_idempotency_key = ?",
                Integer.class,
                "proposal-retry-key")).isEqualTo(1);
    }

    @Test
    void concurrentRetriesCreateOnlyOneApproval() throws Exception {
        var authentication = authenticationService.register(
                "mcp-concurrent-" + UUID.randomUUID() + "@example.com",
                "MCP Concurrent",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Concurrent Project", null);
        String token = authentication.token().value();
        Map<String, Object> arguments = Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-concurrent",
                "title", "One approval");
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> callToolAfter(start, token, arguments));
        var second = CompletableFuture.supplyAsync(() -> callToolAfter(start, token, arguments));
        start.countDown();

        JsonNode firstResult = first.join();
        JsonNode secondResult = second.join();
        assertThat(firstResult.at("/result/isError").asBoolean()).isFalse();
        assertThat(secondResult.at("/result/isError").asBoolean()).isFalse();
        assertThat(firstResult.at("/result/structuredContent/approvalId").asText())
                .isEqualTo(secondResult.at("/result/structuredContent/approvalId").asText());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_task_action where project_id = ?"
                        + " and proposal_idempotency_key = ?",
                Integer.class,
                project.id(),
                "proposal-concurrent")).isEqualTo(1);
    }

    @Test
    void mcpCreateCannotUseTheLowRiskAutomaticConfirmationEndpoint() throws Exception {
        var authentication = authenticationService.register(
                "mcp-manual-" + UUID.randomUUID() + "@example.com",
                "MCP Manual",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Manual Project", null);
        JsonNode proposal = callTool(authentication.token().value(), "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-manual-only",
                "title", "Manual approval required"));
        UUID approvalId = UUID.fromString(
                proposal.at("/result/structuredContent/approvalId").asText());
        jdbcTemplate.update(
                "update agent_task_action set created_at = created_at - interval '61 seconds' where id = ?",
                approvalId);

        HttpResponse<String> response = decideResponse(
                authentication.token().value(),
                project.id(),
                approvalId,
                "auto-confirm",
                "mcp-auto-forbidden");

        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(403);
        assertThat(taskService.list(project.id(), actor)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select status from agent_task_action where id = ?",
                String.class,
                approvalId)).isEqualTo("PENDING");
    }

    @Test
    void mcpEndpointRequiresBearerAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("X-Request-Id", "mcp-auth-required")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("X-Request-Id")).contains("mcp-auth-required");
        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("requestId").asText()).isEqualTo("mcp-auth-required");
    }

    @Test
    void projectIsolationAndSchemaValidationReturnSafeToolErrors() throws Exception {
        var ownerAuthentication = authenticationService.register(
                "mcp-owner-" + UUID.randomUUID() + "@example.com",
                "MCP Owner",
                "integration-password");
        var outsiderAuthentication = authenticationService.register(
                "mcp-outsider-" + UUID.randomUUID() + "@example.com",
                "MCP Outsider",
                "integration-password");
        var owner = new AuthenticatedActor(ownerAuthentication.user().id(), false);
        var project = projectService.createProject(owner, "MCP Private Project", null);
        wikiPageService.create(project.id(), owner, "Private Wiki", "Private body");
        var task = taskService.create(project.id(), owner, "Existing Task", null,
                TaskStatus.TODO, TaskPriority.MEDIUM);

        JsonNode forbidden = callTool(outsiderAuthentication.token().value(), "search_wiki", Map.of(
                "projectId", project.id().toString(),
                "query", "private"));
        assertThat(forbidden.at("/result/isError").asBoolean()).isTrue();
        assertThat(forbidden.at("/result/structuredContent").isMissingNode()).isTrue();
        assertThat(forbidden.at("/result/content/0/text").asText())
                .doesNotContain("Private Wiki", "Private body");

        JsonNode injected = callTool(ownerAuthentication.token().value(), "get_task", Map.of(
                "projectId", project.id().toString(),
                "taskId", task.id().toString(),
                "actorUserId", outsiderAuthentication.user().id().toString()));
        assertThat(injected.at("/result/isError").asBoolean()).isTrue();

        JsonNode invalidUuid = callTool(ownerAuthentication.token().value(), "get_task", Map.of(
                "projectId", "not-a-uuid",
                "taskId", task.id().toString()));
        JsonNode oversized = callTool(ownerAuthentication.token().value(), "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-oversized",
                "title", "Oversized",
                "description", "x".repeat(10001)));
        JsonNode negativeVersion = callTool(ownerAuthentication.token().value(), "update_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-negative",
                "taskId", task.id().toString(),
                "expectedTaskVersion", -1,
                "title", "Invalid"));
        JsonNode fractionalVersion = callTool(ownerAuthentication.token().value(), "update_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-fractional",
                "taskId", task.id().toString(),
                "expectedTaskVersion", 0.5,
                "title", "Invalid"));
        JsonNode missingArguments = post(
                ownerAuthentication.token().value(),
                "{\"jsonrpc\":\"2.0\",\"id\":\"missing-arguments\","
                        + "\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"create_task\"}}");

        assertThat(invalidUuid.at("/result/isError").asBoolean()).isTrue();
        assertThat(oversized.at("/result/isError").asBoolean()).isTrue();
        assertThat(negativeVersion.at("/result/isError").asBoolean()).isTrue();
        assertThat(fractionalVersion.at("/result/isError").asBoolean()).isTrue();
        assertThat(missingArguments.at("/result/isError").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from agent_task_action where project_id = ?",
                Integer.class,
                project.id())).isZero();
    }

    @Test
    void mcpPendingApprovalCanBeRejectedWithoutAgentResumeOrTaskWrite() throws Exception {
        var authentication = authenticationService.register(
                "mcp-reject-" + UUID.randomUUID() + "@example.com",
                "MCP Reject",
                "integration-password");
        var actor = new AuthenticatedActor(authentication.user().id(), false);
        var project = projectService.createProject(actor, "MCP Reject Project", null);
        JsonNode proposal = callTool(authentication.token().value(), "create_task", Map.of(
                "projectId", project.id().toString(),
                "idempotencyKey", "proposal-reject-once",
                "title", "Do not create"));

        UUID approvalId = UUID.fromString(
                proposal.at("/result/structuredContent/approvalId").asText());
        JsonNode rejected = decide(
                authentication.token().value(), project.id(), approvalId, "reject", "mcp-reject-once");

        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(taskService.list(project.id(), actor)).isEmpty();
    }

    private JsonNode callToolAfter(
            CountDownLatch start,
            String token,
            Map<String, Object> arguments) {
        try {
            start.await();
            return callTool(token, "create_task", arguments);
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode confirm(String token, UUID projectId, UUID approvalId, String key) throws Exception {
        return decide(token, projectId, approvalId, "confirm", key);
    }

    private JsonNode decide(
            String token,
            UUID projectId,
            UUID approvalId,
            String decision,
            String key) throws Exception {
        HttpResponse<String> response = decideResponse(token, projectId, approvalId, decision, key);
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> decideResponse(
            String token,
            UUID projectId,
            UUID approvalId,
            String decision,
            String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/projects/" + projectId
                                + "/agent/actions/" + approvalId + "/" + decision))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode callTool(String token, String name, Map<String, Object> arguments) throws Exception {
        return post(token, objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments))));
    }

    private JsonNode post(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }
}
