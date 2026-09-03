package com.agentforge.core.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.agentforge.core.project.domain.Project;
import com.agentforge.core.project.domain.ProjectRepository;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.user.UserDirectory;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserDirectory userDirectory;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository,
                userDirectory,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createProjectUsesAuthenticatedUserAsOwner() {
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        when(projectRepository.existsByOwnerIdAndName(userId, "AgentForge")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectView result = projectService.createProject(actor, "  AgentForge  ", "  AI workspace  ");

        assertThat(result.ownerId()).isEqualTo(userId);
        assertThat(result.name()).isEqualTo("AgentForge");
        assertThat(result.description()).isEqualTo("AI workspace");
        verify(userDirectory).requireUserExists(userId);
    }

    @Test
    void createProjectRejectsDuplicateNameForOwner() {
        UUID userId = UUID.randomUUID();
        when(projectRepository.existsByOwnerIdAndName(userId, "AgentForge")).thenReturn(true);

        assertThatThrownBy(() -> projectService.createProject(
                new AuthenticatedActor(userId, false),
                "AgentForge",
                null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("name");
    }

    @Test
    void getProjectRejectsCrossUserButAllowsAdmin() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Project project = Project.create(ownerId, "AgentForge", null, NOW);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> projectService.getProject(
                projectId,
                new AuthenticatedActor(UUID.randomUUID(), false)))
                .isInstanceOf(ForbiddenException.class);

        assertThat(projectService.getProject(
                projectId,
                new AuthenticatedActor(UUID.randomUUID(), true)).ownerId())
                .isEqualTo(ownerId);
    }

    @Test
    void getProjectRejectsUnknownId() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject(
                projectId,
                new AuthenticatedActor(UUID.randomUUID(), false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(projectId.toString());
    }

    @Test
    void listProjectsIsAlwaysScopedToAuthenticatedUser() {
        UUID userId = UUID.randomUUID();
        when(projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertThat(projectService.listProjects(new AuthenticatedActor(userId, true))).isEmpty();
        verify(userDirectory).requireUserExists(userId);
        verify(projectRepository).findAllByOwnerIdOrderByCreatedAtDesc(userId);
    }
}
