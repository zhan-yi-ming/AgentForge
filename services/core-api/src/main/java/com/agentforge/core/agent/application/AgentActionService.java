package com.agentforge.core.agent.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@Service
public class AgentActionService {

    private final AgentTaskActionRepository actions;
    private final ProjectAccess projectAccess;
    private final TaskService taskService;
    private final Clock clock;

    public AgentActionService(
            AgentTaskActionRepository actions,
            ProjectAccess projectAccess,
            TaskService taskService,
            Clock clock) {
        this.actions = actions;
        this.projectAccess = projectAccess;
        this.taskService = taskService;
        this.clock = clock;
    }

    @Transactional
    public Optional<AgentActionView> createPending(
            UUID projectId,
            AuthenticatedActor actor,
            UUID conversationId,
            ToolProposal proposal) {
        projectAccess.requireAccess(projectId, actor);
        NormalizedProposal normalized;
        try {
            normalized = normalize(proposal);
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (normalized.type() == AgentActionType.UPDATE_TASK) {
            TaskView current = taskService.get(projectId, normalized.taskId(), actor);
            if (current.version() != normalized.expectedVersion()) {
                throw new ConflictException("The Task version is stale.");
            }
        }
        AgentTaskAction action = AgentTaskAction.pending(
                projectId,
                actor.userId(),
                conversationId,
                normalized.type(),
                normalized.taskId(),
                normalized.title(),
                normalized.description(),
                normalized.status(),
                normalized.priority(),
                normalized.expectedVersion(),
                Instant.now(clock));
        return Optional.of(AgentActionView.from(actions.save(action), null));
    }

    @Transactional
    public AgentActionView confirm(UUID projectId, UUID actionId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        AgentTaskAction action = findForDecision(projectId, actionId, actor);
        if (action.getStatus() == AgentActionStatus.REJECTED) {
            throw new ConflictException("The Agent action was rejected.");
        }
        if (action.getStatus() == AgentActionStatus.EXECUTED) {
            return AgentActionView.from(action, taskService.get(projectId, action.getResultTaskId(), actor));
        }

        TaskView result = action.getActionType() == AgentActionType.CREATE_TASK
                ? executeCreate(projectId, actor, action)
                : executeUpdate(projectId, actor, action);
        action.markExecuted(result.id(), Instant.now(clock));
        return AgentActionView.from(actions.save(action), result);
    }

    @Transactional
    public AgentActionView reject(UUID projectId, UUID actionId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        AgentTaskAction action = findForDecision(projectId, actionId, actor);
        if (action.getStatus() == AgentActionStatus.EXECUTED) {
            throw new ConflictException("The Agent action was already executed.");
        }
        if (action.getStatus() == AgentActionStatus.PENDING) {
            action.reject(Instant.now(clock));
            actions.save(action);
        }
        return AgentActionView.from(action, null);
    }

    private TaskView executeCreate(UUID projectId, AuthenticatedActor actor, AgentTaskAction action) {
        return taskService.create(
                projectId,
                actor,
                action.getTitle(),
                action.getDescription(),
                action.getTaskStatus(),
                action.getPriority());
    }

    private TaskView executeUpdate(UUID projectId, AuthenticatedActor actor, AgentTaskAction action) {
        TaskView current = taskService.get(projectId, action.getTaskId(), actor);
        return taskService.update(
                projectId,
                action.getTaskId(),
                actor,
                action.getTitle() == null ? current.title() : action.getTitle(),
                action.getDescription() == null ? current.description() : action.getDescription(),
                action.getTaskStatus() == null ? current.status() : action.getTaskStatus(),
                action.getPriority() == null ? current.priority() : action.getPriority(),
                action.getExpectedTaskVersion());
    }

    private AgentTaskAction findForDecision(UUID projectId, UUID actionId, AuthenticatedActor actor) {
        AgentTaskAction action = actions.findByProjectIdAndIdForUpdate(projectId, actionId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent action not found: " + actionId));
        if (!actor.admin() && !action.getRequestedByUserId().equals(actor.userId())) {
            throw new ForbiddenException("The Agent action belongs to another user.");
        }
        return action;
    }

    private NormalizedProposal normalize(ToolProposal proposal) {
        if (proposal == null || !StringUtils.hasText(proposal.actionType())) {
            throw invalidProposal();
        }
        try {
            AgentActionType type = AgentActionType.valueOf(proposal.actionType());
            String title = normalizeText(proposal.title(), 200);
            String description = normalizeText(proposal.description(), 10000);
            TaskStatus status = proposal.status() == null ? null : TaskStatus.valueOf(proposal.status());
            TaskPriority priority = proposal.priority() == null ? null : TaskPriority.valueOf(proposal.priority());
            if (type == AgentActionType.CREATE_TASK) {
                if (title == null || proposal.taskId() != null || proposal.expectedVersion() != null) {
                    throw invalidProposal();
                }
                return new NormalizedProposal(
                        type, null, null, title, description,
                        status == null ? TaskStatus.TODO : status,
                        priority == null ? TaskPriority.MEDIUM : priority);
            }
            if (proposal.taskId() == null || proposal.expectedVersion() == null || proposal.expectedVersion() < 0
                    || (title == null && description == null && status == null && priority == null)) {
                throw invalidProposal();
            }
            return new NormalizedProposal(
                    type, proposal.taskId(), proposal.expectedVersion(), title, description, status, priority);
        }
        catch (IllegalArgumentException exception) {
            throw invalidProposal();
        }
    }

    private String normalizeText(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw invalidProposal();
        }
        return trimmed;
    }

    private IllegalArgumentException invalidProposal() {
        return new IllegalArgumentException("Agent Service returned an invalid tool proposal.");
    }

    private record NormalizedProposal(
            AgentActionType type,
            UUID taskId,
            Long expectedVersion,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority) {
    }
}
