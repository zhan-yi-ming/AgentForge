package com.agentforge.core.graph.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.agentforge.core.graph.domain.GraphModel.SourceType;

@Component
public class GraphSourceSyncQueue {
    private final JdbcTemplate jdbc;
    public GraphSourceSyncQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void mark(UUID projectId, SourceType type, UUID sourceId) {
        jdbc.update("""
            INSERT INTO graph_source_sync(project_id, source_type, source_id)
            VALUES (?, ?, ?)
            ON CONFLICT (project_id, source_type, source_id)
            DO UPDATE SET updated_at=now(), next_attempt_at=now(), attempts=0
            """, projectId, type.name(), sourceId);
    }

    public record Status(long pending, long retrying) {}
    public Status status(UUID projectId) {
        return jdbc.queryForObject("""
            SELECT count(*), count(*) FILTER (WHERE attempts>0)
            FROM graph_source_sync WHERE project_id=?
            """, (rs,i) -> new Status(rs.getLong(1),rs.getLong(2)), projectId);
    }
    public void rebuild(UUID projectId) {
        jdbc.update("""
            INSERT INTO graph_source_sync(project_id, source_type, source_id)
            SELECT project_id, 'WIKI', id FROM wiki_page WHERE project_id=?
            ON CONFLICT (project_id, source_type, source_id)
            DO UPDATE SET updated_at=now(), next_attempt_at=now(), attempts=0
            """, projectId);
        jdbc.update("""
            INSERT INTO graph_source_sync(project_id, source_type, source_id)
            SELECT project_id, 'TASK', id FROM task_item WHERE project_id=?
            ON CONFLICT (project_id, source_type, source_id)
            DO UPDATE SET updated_at=now(), next_attempt_at=now(), attempts=0
            """, projectId);
    }
}
