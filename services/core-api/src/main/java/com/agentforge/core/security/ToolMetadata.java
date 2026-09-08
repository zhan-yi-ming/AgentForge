package com.agentforge.core.security;

import com.agentforge.core.user.UserRole;

public record ToolMetadata(UserRole requiredRole, RiskLevel riskLevel, boolean needApproval) {
}
