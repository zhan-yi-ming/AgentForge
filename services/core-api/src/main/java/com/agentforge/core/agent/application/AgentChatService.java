package com.agentforge.core.agent.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;

@Service
public class AgentChatService {

    private final ProjectAccess projectAccess;
    private final AgentServiceClient agentServiceClient;

    public AgentChatService(ProjectAccess projectAccess, AgentServiceClient agentServiceClient) {
        this.projectAccess = projectAccess;
        this.agentServiceClient = agentServiceClient;
    }

    public AgentChatResult chat(
            UUID projectId,
            AuthenticatedActor actor,
            String message,
            UUID conversationId,
            String requestId) {
        projectAccess.requireAccess(projectId, actor);
        return agentServiceClient.chat(projectId, actor.userId(), message.trim(), conversationId, requestId);
    }
}
