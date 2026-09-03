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
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.user.UserDirectory;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserDirectory userDirectory;
    private final Clock clock;

    public ProjectService(ProjectRepository projectRepository, UserDirectory userDirectory, Clock clock) {
        this.projectRepository = projectRepository;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    @Transactional
    public ProjectView createProject(UUID ownerId, String name, String description) {
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
    public ProjectView getProject(UUID projectId) {
        return ProjectView.from(projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId)));
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(UUID ownerId) {
        userDirectory.requireUserExists(ownerId);
        return projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(ProjectView::from)
                .toList();
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
