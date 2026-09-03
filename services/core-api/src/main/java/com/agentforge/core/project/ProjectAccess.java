package com.agentforge.core.project;

import java.util.UUID;

import com.agentforge.core.security.AuthenticatedActor;

public interface ProjectAccess {

    void requireAccess(UUID projectId, AuthenticatedActor actor);
}
