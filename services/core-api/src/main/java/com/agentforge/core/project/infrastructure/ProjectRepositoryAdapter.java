package com.agentforge.core.project.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.agentforge.core.project.domain.Project;
import com.agentforge.core.project.domain.ProjectRepository;

@Repository
class ProjectRepositoryAdapter implements ProjectRepository {

    private final SpringDataProjectRepository repository;

    ProjectRepositoryAdapter(SpringDataProjectRepository repository) {
        this.repository = repository;
    }

    @Override
    public Project save(Project project) {
        return repository.save(project);
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public boolean existsByOwnerIdAndName(UUID ownerId, String name) {
        return repository.existsByOwnerIdAndName(ownerId, name);
    }

    @Override
    public List<Project> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId) {
        return repository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);
    }
}

interface SpringDataProjectRepository extends JpaRepository<Project, UUID> {

    boolean existsByOwnerIdAndName(UUID ownerId, String name);

    List<Project> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
