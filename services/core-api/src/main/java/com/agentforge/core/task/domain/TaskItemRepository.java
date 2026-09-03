package com.agentforge.core.task.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskItemRepository {

    TaskItem save(TaskItem task);

    Optional<TaskItem> findByProjectIdAndId(UUID projectId, UUID id);

    List<TaskItem> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId);

    void delete(TaskItem task);
}
