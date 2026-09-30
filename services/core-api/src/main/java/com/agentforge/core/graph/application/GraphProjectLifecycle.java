package com.agentforge.core.graph.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@Component
public class GraphProjectLifecycle {
    private final JdbcTemplate jdbc;

    public GraphProjectLifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long beginClear(UUID projectId) {
        ensure(projectId);
        var state = lockForUpdate(projectId);
        if (state.resetting() && !state.stale())
            throw new ServiceUnavailableException("Graph reset is in progress.");
        long generation = state.generation() + 1;
        jdbc.update("UPDATE graph_project_state SET generation=?,resetting=true,updated_at=now() WHERE project_id=?",
            generation, projectId);
        jdbc.update("DELETE FROM graph_resolution_member WHERE project_id=?", projectId);
        jdbc.update("DELETE FROM graph_source_sync WHERE project_id=?", projectId);
        return generation;
    }

    @Transactional
    public void finishClear(UUID projectId, long generation) {
        jdbc.update("""
            UPDATE graph_project_state SET resetting=false,updated_at=now()
            WHERE project_id=? AND generation=?
            """, projectId, generation);
    }

    public long lockAvailable(UUID projectId) {
        ensure(projectId);
        var state = lockForShare(projectId);
        if (state.resetting()) throw new ServiceUnavailableException("Graph reset is in progress.");
        return state.generation();
    }

    public boolean lockForSync(UUID projectId) {
        ensure(projectId);
        return !lockForShare(projectId).resetting();
    }

    private void ensure(UUID projectId) {
        jdbc.update("INSERT INTO graph_project_state(project_id) VALUES (?) ON CONFLICT DO NOTHING", projectId);
    }

    private State lockForUpdate(UUID projectId) {
        return state(projectId, "FOR UPDATE");
    }

    private State lockForShare(UUID projectId) {
        return state(projectId, "FOR SHARE");
    }

    private State state(UUID projectId, String lock) {
        return jdbc.queryForObject("""
            SELECT generation,resetting,updated_at < now()-interval '5 minutes' AS stale
            FROM graph_project_state WHERE project_id=?
            """ + lock, (rs, row) -> new State(rs.getLong("generation"),
                rs.getBoolean("resetting"), rs.getBoolean("stale")), projectId);
    }

    private record State(long generation, boolean resetting, boolean stale) {}
}