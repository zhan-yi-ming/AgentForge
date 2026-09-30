package com.agentforge.core.graph.application;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.agentforge.core.graph.domain.GraphExtraction;
import com.agentforge.core.graph.domain.GraphStore;
import com.agentforge.core.graph.domain.GraphModel.SourceType;

@Service
public class GraphSyncProcessor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GraphSyncProcessor.class);
    private final JdbcTemplate jdbc;
    private final GraphStore store;
    private final GraphProjectLifecycle lifecycle;
    private final boolean enabled;
    public GraphSyncProcessor(JdbcTemplate jdbc, GraphStore store, GraphProjectLifecycle lifecycle,
        @Value("${agentforge.graph.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.store=store; this.lifecycle=lifecycle; this.enabled=enabled;
    }
    private record Pending(UUID projectId, SourceType type, UUID sourceId) {}

    @Transactional
    public boolean processOne() {
        if (!enabled) return false;
        var candidates=jdbc.query("""
            SELECT q.project_id, q.source_type, q.source_id
            FROM graph_source_sync q
            LEFT JOIN graph_project_state s ON s.project_id=q.project_id
            WHERE q.next_attempt_at<=now() AND coalesce(s.resetting,false)=false
            ORDER BY q.next_attempt_at, q.updated_at
            LIMIT 1
            """, (rs,i) -> new Pending(rs.getObject(1,UUID.class),
                SourceType.valueOf(rs.getString(2)),rs.getObject(3,UUID.class)));
        if (candidates.isEmpty()) return false;
        var candidate=candidates.getFirst();
        if (!lifecycle.lockForSync(candidate.projectId())) return false;
        var rows=jdbc.query("""
            SELECT project_id, source_type, source_id FROM graph_source_sync
            WHERE project_id=? AND source_type=? AND source_id=? AND next_attempt_at<=now()
            FOR UPDATE SKIP LOCKED
            """, (rs,i) -> new Pending(rs.getObject(1,UUID.class),
                SourceType.valueOf(rs.getString(2)),rs.getObject(3,UUID.class)),
            candidate.projectId(),candidate.type().name(),candidate.sourceId());
        if (rows.isEmpty()) return false;
        var row=rows.getFirst();
        try {
            var document=read(row);
            store.replaceSource(row.projectId(),row.type(),row.sourceId(),
                document==null ? null : GraphExtraction.extract(document));
            jdbc.update("DELETE FROM graph_source_sync WHERE project_id=? AND source_type=? AND source_id=?",
                row.projectId(),row.type().name(),row.sourceId());
        } catch (RuntimeException failure) {
            log.warn("Graph source sync deferred for project={}, type={}, source={}, failure={}",
                row.projectId(), row.type(), row.sourceId(), failure.getClass().getSimpleName());
            jdbc.update("""
                UPDATE graph_source_sync
                SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds'
                WHERE project_id=? AND source_type=? AND source_id=?
                """, row.projectId(),row.type().name(),row.sourceId());
        }
        return true;
    }

    private GraphExtraction.Document read(Pending row) {
        String sql=row.type()==SourceType.WIKI
            ? "SELECT title, content, version FROM wiki_page WHERE project_id=? AND id=?"
            : "SELECT title, description, version FROM task_item WHERE project_id=? AND id=?";
        var docs=jdbc.query(sql,(rs,i)->new GraphExtraction.Document(row.projectId(),row.type(),
            row.sourceId(),rs.getLong("version"),rs.getString("title"),rs.getString(2)),
            row.projectId(),row.sourceId());
        return docs.isEmpty() ? null : docs.getFirst();
    }
}
