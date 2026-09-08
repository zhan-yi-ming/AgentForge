package com.agentforge.core.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.user.UserRole;

@Component
public class ToolRiskEngine {

    private static final Map<ToolOperation, ToolMetadata> POLICIES = policies();

    private final ProjectAccess projectAccess;

    public ToolRiskEngine(ProjectAccess projectAccess) {
        this.projectAccess = projectAccess;
    }

    public ToolMetadata metadata(ToolOperation operation) {
        return POLICIES.get(operation);
    }

    public ToolMetadata authorize(ToolOperation operation, UUID projectId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        ToolMetadata metadata = metadata(operation);
        if (metadata.requiredRole() == UserRole.ADMIN && !actor.admin()) {
            throw new ForbiddenException("This operation requires the ADMIN role.");
        }
        return metadata;
    }

    private static Map<ToolOperation, ToolMetadata> policies() {
        EnumMap<ToolOperation, ToolMetadata> policies = new EnumMap<>(ToolOperation.class);
        policies.put(ToolOperation.SEARCH_WIKI, new ToolMetadata(UserRole.USER, RiskLevel.READ, false));
        policies.put(ToolOperation.CREATE_WIKI, new ToolMetadata(UserRole.USER, RiskLevel.LOW, false));
        policies.put(ToolOperation.UPDATE_WIKI, new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, true));
        policies.put(ToolOperation.DELETE_WIKI, new ToolMetadata(UserRole.ADMIN, RiskLevel.HIGH, true));
        policies.put(ToolOperation.GET_TASK, new ToolMetadata(UserRole.USER, RiskLevel.READ, false));
        policies.put(ToolOperation.CREATE_TASK, new ToolMetadata(UserRole.USER, RiskLevel.LOW, true));
        policies.put(ToolOperation.UPDATE_TASK, new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, true));
        policies.put(ToolOperation.DELETE_TASK, new ToolMetadata(UserRole.ADMIN, RiskLevel.HIGH, true));
        return Map.copyOf(policies);
    }
}
