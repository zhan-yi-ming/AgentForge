package com.agentforge.core.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.security.application.AuthenticationService;
import com.agentforge.core.project.application.ProjectService;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.graph.infrastructure.Neo4jGraphStore;
import com.agentforge.core.shared.error.ServiceUnavailableException;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "agentforge.security.jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "agentforge.agent-service.internal-token=test-only-internal-token",
    "agentforge.core-internal.token=test-only-core-token",
    "agentforge.graph.enabled=true", "agentforge.graph.uri=bolt://127.0.0.1:1",
    "agentforge.graph.password=test-only-password"
})
class GraphDependencyIsolationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @Autowired MockMvc mvc;
    @Autowired AuthenticationService auth;
    @Autowired ProjectService projects;
    @Autowired WikiPageService wiki;
    @Test
    void unavailableGraphReturnsGeneric503WhileHealthAndWikiStillWork() throws Exception {
        var registration=auth.register(UUID.randomUUID()+"@isolation.test","Owner","test-password");
        var actor=new AuthenticatedActor(registration.user().id(),false);
        var project=projects.createProject(actor,"Isolation",null);
        mvc.perform(get("/api/v1/projects/"+project.id()+"/graph/entities")
            .with(jwt().jwt(j -> j.subject(actor.userId().toString()).claim("roles",java.util.List.of("USER")))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value("Graph service is unavailable."));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        var page=wiki.create(project.id(),actor,"Still works","Unchanged business service");
        assertThat(wiki.get(project.id(),page.id(),actor).content()).isEqualTo("Unchanged business service");
    }
    @Test
    void disabledAdapterFailsClosedWithoutConnecting() {
        try(var store=new AutoCloseableGraph()) {
            assertThatThrownBy(() -> store.delegate.entities(UUID.randomUUID(),"",1))
                .isInstanceOf(ServiceUnavailableException.class).hasMessage("Graph service is unavailable.");
        }
    }
    private static class AutoCloseableGraph implements AutoCloseable {
        final Neo4jGraphStore delegate=new Neo4jGraphStore(false,"not-a-valid-uri","unused","unused");
        @Override public void close() { delegate.close(); }
    }
}