package com.agentforge.core.project.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    Project save(Project project);

    Optional<Project> findById(UUID id);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);

    List<Project> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
