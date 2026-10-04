package com.agentforge.core.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

class RepositorySourceCrossProcessTest {
    @Test
    @EnabledIfSystemProperty(named = "agentforge.repository.crossprocess", matches = "true")
    void javaClientReceivesRepositoryCitationFromRealPythonChatAndStream() throws Exception {
        var agentDirectory = Path.of("../agent-service").toAbsolutePath().normalize();
        var python = System.getProperty("agentforge.repository.python",
            agentDirectory.resolve(".venv/Scripts/python.exe").toString());
        var parent = Path.of("../../.data").toAbsolutePath().normalize();
        Files.createDirectories(parent);
        var repository = Files.createTempDirectory(parent, "v308-contract-");
        UUID projectId = UUID.randomUUID();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String code = """
            import subprocess, sys
            from pathlib import Path
            from uuid import UUID
            from fastapi import FastAPI
            import uvicorn
            from agentforge_agent.api import router, get_action_runtime, get_responder, get_retrieval_service
            from agentforge_agent.embeddings import HashEmbeddingProvider
            from agentforge_agent.repository_context import RepositoryContextProvider
            from agentforge_agent.retrieval import RetrievalService
            from agentforge_agent.schemas import RagSourcesResponse
            root = Path(sys.argv[2])
            project = UUID(sys.argv[3])
            def git(*args):
                subprocess.run(['git', '-C', str(root), *args], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git('init', '-q')
            (root / 'README.md').write_text('Core entry is services/core-api.', encoding='utf-8')
            git('add', '--', 'README.md')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                'commit', '-qm', 'Document Core entry')
            class Core:
                def fetch_sources(self, *_args):
                    return RagSourcesResponse(projectId=project, snapshotVersion=1,
                                              sources=[], requestId='repository-cross-process')
                def fetch_graph(self, *_args): return []
            class Index:
                def synchronize(self, *_args): pass
                def search(self, *_args): return {}, [], []
            retrieval = RetrievalService(
                Core(), Index(), HashEmbeddingProvider(384), 2, 4, 500,
                RepositoryContextProvider({project: root}))
            def responder(_state): return 'Core entry is documented in README.【来源1】'
            app = FastAPI()
            app.include_router(router)
            app.dependency_overrides[get_retrieval_service] = lambda: retrieval
            app.dependency_overrides[get_responder] = lambda: responder
            app.dependency_overrides[get_action_runtime] = lambda: object()
            uvicorn.run(app, host='127.0.0.1', port=int(sys.argv[1]), log_level='error')
            """;
        var process = new ProcessBuilder(python, "-c", code, Integer.toString(port),
            repository.toString(), projectId.toString()).redirectErrorStream(true);
        process.environment().put("PYTHONPATH", agentDirectory.resolve("src").toString());
        process.environment().put("AGENTFORGE_AGENT_INTERNAL_TOKEN", "test-only-internal-token");
        process.environment().put("AGENTFORGE_CORE_INTERNAL_TOKEN", "test-only-core-token");
        process.environment().put("AGENTFORGE_AGENT_RAG_DB_DSN",
            "postgresql://agentforge:agentforge@localhost:5432/agentforge");
        process.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
        var child = process.start();
        try {
            var url = URI.create("http://127.0.0.1:" + port);
            awaitHealth(url, child);
            var properties = new AgentServiceProperties(url, "test-only-internal-token",
                Duration.ofSeconds(2), Duration.ofSeconds(20));
            var client = new HttpAgentServiceClient(
                new AgentServiceConfiguration().agentServiceRestClient(RestClient.builder(), properties));
            var result = client.chat(projectId, UUID.randomUUID(), false,
                "Core entry", null, "repository-sync");
            assertThat(result.sources()).hasSize(1);
            assertThat(result.sources().getFirst().sourceType()).isEqualTo("REPOSITORY");
            assertThat(result.sources().getFirst().title()).isEqualTo("README.md");
            var events = new ArrayList<com.agentforge.core.agent.application.AgentStreamEvent>();
            client.stream(projectId, UUID.randomUUID(), false,
                "Core entry", null, "repository-stream", events::add);
            assertThat(events.getLast().type()).isEqualTo("complete");
            assertThat(events.getLast().sources().getFirst().sourceType()).isEqualTo("REPOSITORY");
        } finally {
            child.destroy();
            if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
            try (var paths = Files.walk(repository)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (Files.isRegularFile(path)) Files.setAttribute(path, "dos:readonly", false);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void awaitHealth(URI base, Process child) throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var health = HttpRequest.newBuilder(base.resolve("/health")).timeout(Duration.ofSeconds(1))
            .GET().build();
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!child.isAlive()) throw new AssertionError("Python chat process exited before readiness.");
            try {
                if (http.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (java.io.IOException ignored) {
                // The Python process has not yet opened the socket.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Python chat process did not become ready.");
    }
}
