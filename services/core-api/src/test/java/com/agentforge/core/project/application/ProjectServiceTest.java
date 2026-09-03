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
import com.agentforge.core.shared.error.ConflictException;
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
    void createProjectChecksOwnerAndNormalizesInput() {
        UUID ownerId = UUID.randomUUID();
        when(projectRepository.existsByOwnerIdAndName(ownerId, "AgentForge")).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProjectView result = projectService.createProject(
                ownerId,
                "  AgentForge  ",
                "  AI workspace  ");

        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.name()).isEqualTo("AgentForge");
        assertThat(result.description()).isEqualTo("AI workspace");
        assertThat(result.createdAt()).isEqualTo(NOW);
        verify(userDirectory).requireUserExists(ownerId);
    }

    @Test
    void createProjectRejectsDuplicateNameForOwner() {
        UUID ownerId = UUID.randomUUID();
        when(projectRepository.existsByOwnerIdAndName(ownerId, "AgentForge")).thenReturn(true);

        assertThatThrownBy(() -> projectService.createProject(ownerId, "AgentForge", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("name");
    }

    @Test
    void getProjectRejectsUnknownId() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject(projectId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(projectId.toString());
    }

    @Test
    void listProjectsChecksOwnerBoundary() {
        UUID ownerId = UUID.randomUUID();
        when(projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)).thenReturn(List.of());

        assertThat(projectService.listProjects(ownerId)).isEmpty();
        verify(userDirectory).requireUserExists(ownerId);
    }
}
