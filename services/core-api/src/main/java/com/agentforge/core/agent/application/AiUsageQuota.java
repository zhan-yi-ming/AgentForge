package com.agentforge.core.agent.application;

import java.util.UUID;

public interface AiUsageQuota {

    void consume(UUID userId);
}
