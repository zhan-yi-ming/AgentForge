package com.agentforge.core.agent.application;

import java.util.UUID;

public record ToolProposal(
        String actionType,
        UUID taskId,
        Long expectedVersion,
        String title,
        String description,
        String status,
        String priority,
        UUID actionWorkflowId) {

    public ToolProposal(
            String actionType,
            UUID taskId,
            Long expectedVersion,
            String title,
            String description,
            String status,
            String priority) {
        this(actionType, taskId, expectedVersion, title, description, status, priority, null);
    }
}
