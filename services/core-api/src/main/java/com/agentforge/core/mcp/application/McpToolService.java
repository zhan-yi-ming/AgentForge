package com.agentforge.core.mcp.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.ToolOperation;
import com.agentforge.core.security.ToolRiskEngine;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.agent.application.AgentActionService;
import com.agentforge.core.agent.application.AgentActionView;
import com.agentforge.core.agent.application.ToolProposal;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.wiki.application.WikiPageView;

@Service
public class McpToolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolService.class);

    public static final String ACTOR_CONTEXT_KEY = "agentforge.actor";
    public static final String REQUEST_ID_CONTEXT_KEY = "agentforge.request-id";

    private final WikiPageService wikiPages;
    private final TaskService tasks;
    private final AgentActionService actions;
    private final ToolRiskEngine riskEngine;

    public McpToolService(
            WikiPageService wikiPages,
            TaskService tasks,
            AgentActionService actions,
            ToolRiskEngine riskEngine) {
        this.wikiPages = wikiPages;
        this.tasks = tasks;
        this.actions = actions;
        this.riskEngine = riskEngine;
    }

    public McpSchema.CallToolResult searchWiki(
            McpTransportContext context,
            McpSchema.CallToolRequest request) {
        try {
            AuthenticatedActor actor = actor(context);
            UUID projectId = uuid(arguments(request), "projectId");
            String query = requiredString(arguments(request), "query").toLowerCase(Locale.ROOT);
            List<Map<String, Object>> matches = wikiPages.list(projectId, actor).stream()
                    .filter(page -> page.title().toLowerCase(Locale.ROOT).contains(query)
                            || page.content().toLowerCase(Locale.ROOT).contains(query))
                    .map(this::wikiSummary)
                    .toList();
            return success(Map.of("items", matches), "Found " + matches.size() + " Wiki page(s).");
        }
        catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    public McpSchema.CallToolResult getTask(
            McpTransportContext context,
            McpSchema.CallToolRequest request) {
        try {
            AuthenticatedActor actor = actor(context);
            UUID projectId = uuid(arguments(request), "projectId");
            UUID taskId = uuid(arguments(request), "taskId");
            TaskView task = tasks.get(projectId, taskId, actor);
            return success(taskMap(task), "Task " + task.id() + " loaded.");
        }
        catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    public McpSchema.CallToolResult createTask(
            McpTransportContext context,
            McpSchema.CallToolRequest request) {
        try {
            AuthenticatedActor actor = actor(context);
            UUID projectId = uuid(arguments(request), "projectId");
            ToolProposal proposal = new ToolProposal(
                    "CREATE_TASK",
                    null,
                    null,
                    requiredString(arguments(request), "title"),
                    optionalString(arguments(request), "description"),
                    optionalString(arguments(request), "status"),
                    optionalString(arguments(request), "priority"));
            AgentActionView action = actions.createPendingMcp(
                            projectId,
                            actor,
                            proposal,
                            requiredString(arguments(request), "idempotencyKey"),
                            requestId(context))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid create_task arguments."));
            return approvalResult(action, riskEngine.metadata(ToolOperation.CREATE_TASK).riskLevel().name());
        }
        catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    public McpSchema.CallToolResult updateTask(
            McpTransportContext context,
            McpSchema.CallToolRequest request) {
        try {
            AuthenticatedActor actor = actor(context);
            UUID projectId = uuid(arguments(request), "projectId");
            ToolProposal proposal = new ToolProposal(
                    "UPDATE_TASK",
                    uuid(arguments(request), "taskId"),
                    requiredLong(arguments(request), "expectedTaskVersion"),
                    optionalString(arguments(request), "title"),
                    optionalString(arguments(request), "description"),
                    optionalString(arguments(request), "status"),
                    optionalString(arguments(request), "priority"));
            AgentActionView action = actions.createPendingMcp(
                            projectId,
                            actor,
                            proposal,
                            requiredString(arguments(request), "idempotencyKey"),
                            requestId(context))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid update_task arguments."));
            return approvalResult(action, riskEngine.metadata(ToolOperation.UPDATE_TASK).riskLevel().name());
        }
        catch (RuntimeException exception) {
            return failure(context, exception);
        }
    }

    private McpSchema.CallToolResult approvalResult(AgentActionView action, String riskLevel) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvalId", action.id().toString());
        result.put("status", action.status().name());
        result.put("actionType", action.actionType().name());
        result.put("riskLevel", riskLevel);
        Map<String, Object> preview = new LinkedHashMap<>();
        if (action.taskId() != null) {
            preview.put("taskId", action.taskId().toString());
        }
        if (action.title() != null) {
            preview.put("title", action.title());
        }
        if (action.description() != null) {
            preview.put("description", action.description());
        }
        if (action.taskStatus() != null) {
            preview.put("status", action.taskStatus());
        }
        if (action.priority() != null) {
            preview.put("priority", action.priority());
        }
        if (action.expectedVersion() != null) {
            preview.put("expectedTaskVersion", action.expectedVersion());
        }
        result.put("preview", preview);
        return success(
                result,
                "Approval " + action.id() + " has status " + action.status().name() + ".");
    }

    private Map<String, Object> wikiSummary(WikiPageView page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", page.id().toString());
        result.put("projectId", page.projectId().toString());
        result.put("title", page.title());
        result.put("excerpt", excerpt(page.content()));
        result.put("version", page.version());
        result.put("updatedAt", page.updatedAt().toString());
        return result;
    }

    private Map<String, Object> taskMap(TaskView task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.id().toString());
        result.put("projectId", task.projectId().toString());
        result.put("title", task.title());
        if (task.description() != null) {
            result.put("description", task.description());
        }
        result.put("status", task.status().name());
        result.put("priority", task.priority().name());
        result.put("version", task.version());
        result.put("createdAt", task.createdAt().toString());
        result.put("updatedAt", task.updatedAt().toString());
        return result;
    }

    private McpSchema.CallToolResult success(Object structuredContent, String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(structuredContent)
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private McpSchema.CallToolResult failure(
            McpTransportContext context,
            RuntimeException exception) {
        String safeMessage = safeMessage(exception);
        if (!(exception instanceof IllegalArgumentException
                || exception instanceof ForbiddenException
                || exception instanceof ResourceNotFoundException
                || exception instanceof ConflictException)) {
            LOGGER.error(
                    "MCP tool call failed; requestId={}, errorType={}",
                    requestId(context),
                    exception.getClass().getSimpleName());
        }
        return error(safeMessage);
    }

    private AuthenticatedActor actor(McpTransportContext context) {
        Object value = context.get(ACTOR_CONTEXT_KEY);
        if (value instanceof AuthenticatedActor actor) {
            return actor;
        }
        throw new IllegalArgumentException("Authenticated actor context is required.");
    }

    private UUID uuid(Map<String, Object> arguments, String name) {
        try {
            return UUID.fromString(requiredString(arguments, name));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a valid UUID.");
        }
    }

    private long requiredLong(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a non-negative integer.");
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            if (result < 0) {
                throw new ArithmeticException("negative");
            }
            return result;
        }
        catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a non-negative integer.");
        }
    }

    private Map<String, Object> arguments(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    private String requestId(McpTransportContext context) {
        Object value = context.get(REQUEST_ID_CONTEXT_KEY);
        return value instanceof String requestId && !requestId.isBlank() ? requestId : "mcp";
    }

    private String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string.");
        }
        return text;
    }

    private String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string.");
        }
        return text.trim();
    }

    private String excerpt(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private String safeMessage(RuntimeException exception) {
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        if (exception instanceof ForbiddenException) {
            return "The authenticated user is not allowed to use this project.";
        }
        if (exception instanceof ResourceNotFoundException) {
            return "The requested resource was not found.";
        }
        if (exception instanceof ConflictException) {
            return "The request conflicts with current data.";
        }
        return "The tool call could not be completed.";
    }
}
