package com.agentforge.core.agent.application;

import java.util.UUID;

public record AgentSource(String sourceType, UUID sourceId, String title, String excerpt) {
}
