package com.agentforge.core.task.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.agentforge.core.task.domain.TaskItem;
import com.agentforge.core.task.domain.TaskItemRepository;

@Repository
class TaskItemRepositoryAdapter implements TaskItemRepository {

    private final SpringDataTaskItemRepository repository;

    TaskItemRepositoryAdapter(SpringDataTaskItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public TaskItem save(TaskItem task) {
        return repository.saveAndFlush(task);
    }

    @Override
    public Optional<TaskItem> findByProjectIdAndId(UUID projectId, UUID id) {
        return repository.findByProjectIdAndId(projectId, id);
    }

    @Override
    public List<TaskItem> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId) {
        return repository.findAllByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    @Override
    public void delete(TaskItem task) {
        repository.delete(task);
        repository.flush();
    }
}

interface SpringDataTaskItemRepository extends JpaRepository<TaskItem, UUID> {

    Optional<TaskItem> findByProjectIdAndId(UUID projectId, UUID id);

    List<TaskItem> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId);
}
