package com.agentforge.core.agent.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.agent.domain.AgentAuditEvent;
import com.agentforge.core.agent.domain.AgentAuditEventRepository;
import com.agentforge.core.agent.domain.AgentAuditEventType;
import com.agentforge.core.agent.domain.AgentTaskAction;
import com.agentforge.core.agent.domain.AgentTaskActionRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.ToolOperation;
import com.agentforge.core.security.ToolRiskEngine;
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
    private final AgentAuditEventRepository auditEvents;
    private final ProjectAccess projectAccess;
    private final ToolRiskEngine riskEngine;
    private final TaskService taskService;
    private final Clock clock;

    public AgentActionService(
            AgentTaskActionRepository actions,
            ProjectAccess projectAccess,
            TaskService taskService,
            Clock clock) {
        this(actions, event -> event, projectAccess, taskService, clock);
    }

    public AgentActionService(
            AgentTaskActionRepository actions,
            AgentAuditEventRepository auditEvents,
            ProjectAccess projectAccess,
            TaskService taskService,
            Clock clock) {
        this(actions, auditEvents, projectAccess, new ToolRiskEngine(projectAccess), taskService, clock);
    }

    @Autowired
    public AgentActionService(
            AgentTaskActionRepository actions,
            AgentAuditEventRepository auditEvents,
            ProjectAccess projectAccess,
            ToolRiskEngine riskEngine,
            TaskService taskService,
            Clock clock) {
        this.actions = actions;
        this.auditEvents = auditEvents;
        this.projectAccess = projectAccess;
        this.riskEngine = riskEngine;
        this.taskService = taskService;
        this.clock = clock;
    }

    @Transactional
    public Optional<AgentActionView> createPending(
            UUID projectId,
            AuthenticatedActor actor,
            UUID conversationId,
            ToolProposal proposal) {
        return createPending(projectId, actor, conversationId, proposal, "internal");
    }

    @Transactional
    public Optional<AgentActionView> createPending(
            UUID projectId,
            AuthenticatedActor actor,
            UUID conversationId,
            ToolProposal proposal,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        NormalizedProposal normalized;
        try {
            normalized = normalize(proposal);
        }
        catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        ToolOperation operation = switch (normalized.type()) {
            case CREATE_TASK -> ToolOperation.CREATE_TASK;
            case UPDATE_TASK -> ToolOperation.UPDATE_TASK;
        };
        riskEngine.authorize(operation, projectId, actor);
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
        AgentTaskAction saved = actions.save(action);
        auditEvents.save(AgentAuditEvent.record(
                saved, actor.userId(), AgentAuditEventType.REQUESTED,
                requestId, null, Instant.now(clock)));
        return Optional.of(AgentActionView.from(saved, null));
    }

    @Transactional
    public AgentActionView approve(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        AgentTaskAction action = findForDecision(projectId, actionId, actor);
        ToolOperation operation = operationFor(action);
        riskEngine.authorize(operation, projectId, actor);
        if (action.getStatus() == AgentActionStatus.REJECTED) {
            throw new ConflictException("The Agent action was rejected.");
        }
        if (action.getStatus() == AgentActionStatus.EXECUTED) {
            requireMatchingKey(action, idempotencyKey);
            return AgentActionView.from(action, replayResult(projectId, action, actor));
        }
        if (action.getStatus() == AgentActionStatus.FAILED) {
            requireMatchingKey(action, idempotencyKey);
            return AgentActionView.from(action, null);
        }
        if (action.getStatus() == AgentActionStatus.PENDING) {
            action.approve(idempotencyKey, Instant.now(clock));
            actions.save(action);
            auditEvents.save(AgentAuditEvent.record(
                    action, actor.userId(), AgentAuditEventType.APPROVED,
                    requestId, idempotencyKey, Instant.now(clock)));
        }
        else {
            requireMatchingKey(action, idempotencyKey);
        }
        return AgentActionView.from(action, null);
    }

    @Transactional
    public AgentActionView executeApproved(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        AgentTaskAction action = findForDecision(projectId, actionId, actor);
        riskEngine.authorize(operationFor(action), projectId, actor);
        if (action.getStatus() == AgentActionStatus.REJECTED) {
            throw new ConflictException("The Agent action was rejected.");
        }
        if (action.getStatus() == AgentActionStatus.EXECUTED) {
            requireMatchingKey(action, idempotencyKey);
            return AgentActionView.from(action, replayResult(projectId, action, actor));
        }
        if (action.getStatus() == AgentActionStatus.FAILED) {
            requireMatchingKey(action, idempotencyKey);
            return AgentActionView.from(action, null);
        }
        if (action.getStatus() != AgentActionStatus.APPROVED) {
            throw new ConflictException("The Agent action has not been approved.");
        }
        requireMatchingKey(action, idempotencyKey);
        TaskView result;
        try {
            result = action.getActionType() == AgentActionType.CREATE_TASK
                    ? executeCreate(projectId, actor, action)
                    : executeUpdate(projectId, actor, action);
        }
        catch (ConflictException exception) {
            action.markFailed(Instant.now(clock));
            AgentActionView failed = AgentActionView.from(actions.save(action), null);
            auditEvents.save(AgentAuditEvent.record(
                    action, actor.userId(), AgentAuditEventType.FAILED,
                    requestId, idempotencyKey, Instant.now(clock)));
            return failed;
        }
        action.markExecuted(result.id(), Instant.now(clock));
        AgentActionView executed = AgentActionView.from(actions.save(action), result);
        auditEvents.save(AgentAuditEvent.record(
                action, actor.userId(), AgentAuditEventType.EXECUTED,
                requestId, idempotencyKey, Instant.now(clock)));
        return executed;
    }

    private void requireMatchingKey(AgentTaskAction action, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || !action.hasIdempotencyKey(idempotencyKey)) {
            throw new ConflictException("The approval was already decided with another idempotency key.");
        }
    }

    @Transactional
    public AgentActionView reject(UUID projectId, UUID actionId, AuthenticatedActor actor) {
        return reject(projectId, actionId, actor, "legacy-" + actionId, "internal");
    }

    @Transactional
    public AgentActionView reject(
            UUID projectId,
            UUID actionId,
            AuthenticatedActor actor,
            String idempotencyKey,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        AgentTaskAction action = findForDecision(projectId, actionId, actor);
        riskEngine.authorize(operationFor(action), projectId, actor);
        if (action.getStatus() == AgentActionStatus.REJECTED) {
            requireMatchingKey(action, idempotencyKey);
            return AgentActionView.from(action, null);
        }
        if (action.getStatus() != AgentActionStatus.PENDING) {
            throw new ConflictException("The Agent action can no longer be rejected.");
        }
        action.reject(idempotencyKey, Instant.now(clock));
        AgentActionView rejected = AgentActionView.from(actions.save(action), null);
        auditEvents.save(AgentAuditEvent.record(
                action, actor.userId(), AgentAuditEventType.REJECTED,
                requestId, idempotencyKey, Instant.now(clock)));
        return rejected;
    }

    private ToolOperation operationFor(AgentTaskAction action) {
        return switch (action.getActionType()) {
            case CREATE_TASK -> ToolOperation.CREATE_TASK;
            case UPDATE_TASK -> ToolOperation.UPDATE_TASK;
        };
    }

    private TaskView replayResult(UUID projectId, AgentTaskAction action, AuthenticatedActor actor) {
        try {
            return taskService.get(projectId, action.getResultTaskId(), actor);
        }
        catch (ResourceNotFoundException exception) {
            return null;
        }
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
