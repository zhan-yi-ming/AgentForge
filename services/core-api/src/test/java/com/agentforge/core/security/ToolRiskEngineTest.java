package com.agentforge.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.user.UserRole;

class ToolRiskEngineTest {

    private final ProjectAccess projectAccess = mock(ProjectAccess.class);
    private final ToolRiskEngine engine = new ToolRiskEngine(projectAccess);
    private final UUID projectId = UUID.randomUUID();

    @Test
    void exposesServerOwnedMetadataForEverySupportedOperation() {
        assertThat(engine.metadata(ToolOperation.SEARCH_WIKI))
                .isEqualTo(new ToolMetadata(UserRole.USER, RiskLevel.READ, false));
        assertThat(engine.metadata(ToolOperation.CREATE_TASK))
                .isEqualTo(new ToolMetadata(UserRole.USER, RiskLevel.LOW, true));
        assertThat(engine.metadata(ToolOperation.UPDATE_TASK))
                .isEqualTo(new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, true));
        assertThat(engine.metadata(ToolOperation.UPDATE_WIKI))
                .isEqualTo(new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, true));
        assertThat(engine.metadata(ToolOperation.DELETE_TASK))
                .isEqualTo(new ToolMetadata(UserRole.ADMIN, RiskLevel.HIGH, true));
    }

    @Test
    void userCannotExecuteHighRiskDeleteButAdminCan() {
        AuthenticatedActor user = new AuthenticatedActor(UUID.randomUUID(), false);
        AuthenticatedActor admin = new AuthenticatedActor(UUID.randomUUID(), true);

        assertThatThrownBy(() -> engine.authorize(ToolOperation.DELETE_TASK, projectId, user))
                .isInstanceOf(ForbiddenException.class);
        engine.authorize(ToolOperation.DELETE_TASK, projectId, admin);

        verify(projectAccess).requireAccess(projectId, user);
        verify(projectAccess).requireAccess(projectId, admin);
    }

}
