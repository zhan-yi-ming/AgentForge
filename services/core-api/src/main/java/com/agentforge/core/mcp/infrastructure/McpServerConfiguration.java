package com.agentforge.core.mcp.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.agentforge.core.mcp.application.McpToolService;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.web.RequestIdFilter;

@Configuration
public class McpServerConfiguration {

    @Bean
    McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    HttpServletStatelessServerTransport mcpTransport(McpJsonMapper jsonMapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint("/mcp")
                .contextExtractor(request -> {
                    if (!(request.getUserPrincipal() instanceof JwtAuthenticationToken authentication)) {
                        return McpTransportContext.EMPTY;
                    }
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put(
                            McpToolService.ACTOR_CONTEXT_KEY,
                            AuthenticatedActor.from(authentication.getToken()));
                    Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
                    if (requestId instanceof String value) {
                        metadata.put(McpToolService.REQUEST_ID_CONTEXT_KEY, value);
                    }
                    return McpTransportContext.create(metadata);
                })
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(
            HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("agentforgeMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(
            HttpServletStatelessServerTransport transport,
            McpJsonMapper jsonMapper,
            McpToolService toolService) {
        return McpServer.sync(transport)
                .jsonMapper(jsonMapper)
                .serverInfo("agentforge-core-api", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(tool("search_wiki", "Search authorized Wiki pages in one project.",
                        objectSchema(
                                Map.of(
                                        "projectId", uuidProperty("Project UUID"),
                                        "query", boundedStringProperty("Search text", 1, 500)),
                                List.of("projectId", "query"))), toolService::searchWiki)
                .toolCall(tool("get_task", "Get one authorized Task by project and Task ID.",
                        objectSchema(
                                Map.of(
                                        "projectId", uuidProperty("Project UUID"),
                                        "taskId", uuidProperty("Task UUID")),
                                List.of("projectId", "taskId"))), toolService::getTask)
                .toolCall(tool("create_task", "Create a pending Task approval for human confirmation.",
                        objectSchema(
                                Map.of(
                                        "projectId", uuidProperty("Project UUID"),
                                        "idempotencyKey", idempotencyKeyProperty(),
                                        "title", boundedStringProperty("Task title", 1, 200),
                                        "description", boundedStringProperty("Task description", 1, 10000),
                                        "status", enumProperty(List.of("TODO", "IN_PROGRESS", "DONE")),
                                        "priority", enumProperty(List.of("LOW", "MEDIUM", "HIGH"))),
                                List.of("projectId", "idempotencyKey", "title"))), toolService::createTask)
                .toolCall(tool("update_task", "Create a pending Task update approval for human confirmation.",
                        objectSchema(
                                Map.of(
                                        "projectId", uuidProperty("Project UUID"),
                                        "idempotencyKey", idempotencyKeyProperty(),
                                        "taskId", uuidProperty("Task UUID"),
                                        "expectedTaskVersion", Map.of("type", "integer", "minimum", 0),
                                        "title", boundedStringProperty("Task title", 1, 200),
                                        "description", boundedStringProperty("Task description", 1, 10000),
                                        "status", enumProperty(List.of("TODO", "IN_PROGRESS", "DONE")),
                                        "priority", enumProperty(List.of("LOW", "MEDIUM", "HIGH"))),
                                List.of("projectId", "idempotencyKey", "taskId", "expectedTaskVersion"))), toolService::updateTask)
                .build();
    }

    private McpSchema.Tool tool(String name, String description, Map<String, Object> schema) {
        return McpSchema.Tool.builder(name, schema)
                .description(description)
                .build();
    }

    private Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }

    private Map<String, Object> uuidProperty(String description) {
        return Map.of(
                "type", "string",
                "format", "uuid",
                "description", description);
    }

    private Map<String, Object> boundedStringProperty(
            String description,
            int minimumLength,
            int maximumLength) {
        return Map.of(
                "type", "string",
                "minLength", minimumLength,
                "maxLength", maximumLength,
                "description", description);
    }

    private Map<String, Object> idempotencyKeyProperty() {
        return Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", 100,
                "pattern", "^[A-Za-z0-9._:-]+$",
                "description", "Client-generated proposal idempotency key");
    }

    private Map<String, Object> enumProperty(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }
}
