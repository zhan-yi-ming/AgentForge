package com.agentforge.core.project.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.project.domain.Project;
import com.agentforge.core.project.domain.ProjectRepository;
import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.user.UserDirectory;

@Service
public class ProjectService implements ProjectAccess {

    private final ProjectRepository projectRepository;
    private final UserDirectory userDirectory;
    private final Clock clock;

    public ProjectService(ProjectRepository projectRepository, UserDirectory userDirectory, Clock clock) {
        this.projectRepository = projectRepository;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    @Transactional
    public ProjectView createProject(AuthenticatedActor actor, String name, String description) {
        UUID ownerId = actor.userId();
        userDirectory.requireUserExists(ownerId);
        String normalizedName = name.trim();
        String normalizedDescription = normalizeDescription(description);

        if (projectRepository.existsByOwnerIdAndName(ownerId, normalizedName)) {
            throw new ConflictException("A project with this name already exists for the owner.");
        }

        Project project = Project.create(
                ownerId,
                normalizedName,
                normalizedDescription,
                Instant.now(clock));
        try {
            return ProjectView.from(projectRepository.save(project));
        }
        catch (DataIntegrityViolationException exception) {
            throw new ConflictException("The project conflicts with existing data.");
        }
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(UUID projectId, AuthenticatedActor actor) {
        Project project = findProject(projectId);
        requireOwnerOrAdmin(project, actor);
        return ProjectView.from(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(AuthenticatedActor actor) {
        userDirectory.requireUserExists(actor.userId());
        return projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(actor.userId())
                .stream()
                .map(ProjectView::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireAccess(UUID projectId, AuthenticatedActor actor) {
        requireOwnerOrAdmin(findProject(projectId), actor);
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    private void requireOwnerOrAdmin(Project project, AuthenticatedActor actor) {
        if (!actor.admin() && !project.getOwnerId().equals(actor.userId())) {
            throw new ForbiddenException("The authenticated user cannot access this project.");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
