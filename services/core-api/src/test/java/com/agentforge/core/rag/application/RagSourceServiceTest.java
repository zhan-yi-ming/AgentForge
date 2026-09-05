package com.agentforge.core.rag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ForbiddenException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.shared.error.UnauthorizedException;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;
import com.agentforge.core.user.UserDirectory;
import com.agentforge.core.wiki.application.WikiPageService;
import com.agentforge.core.wiki.application.WikiPageView;

class RagSourceServiceTest {

    @Test
    void listAuthorizesBeforeReadingAndMapsWikiAndTask() {
        UserDirectory users = mock(UserDirectory.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        WikiPageService wiki = mock(WikiPageService.class);
        TaskService tasks = mock(TaskService.class);
        RagSourceService service = new RagSourceService(users, projects, wiki, tasks);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        when(wiki.list(projectId, actor)).thenReturn(List.of(new WikiPageView(
                UUID.randomUUID(), projectId, "Architecture", "# Core", 2, now, now)));
        when(tasks.list(projectId, actor)).thenReturn(List.of(new TaskView(
                UUID.randomUUID(), projectId, "Ship RAG", "Add retrieval", TaskStatus.TODO,
                TaskPriority.HIGH, 1, now, now)));

        List<RagSource> result = service.list(projectId, userId, false);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).sourceType()).isEqualTo("WIKI");
        assertThat(result.get(1).content()).contains("Status: TODO", "Priority: HIGH", "Add retrieval");
        InOrder order = inOrder(users, projects, wiki, tasks);
        order.verify(users).requireUserExists(userId);
        order.verify(projects).requireAccess(projectId, actor);
        order.verify(wiki).list(projectId, actor);
        order.verify(tasks).list(projectId, actor);
    }

    @Test
    void listDoesNotReadSourcesWhenProjectAccessIsRejected() {
        UserDirectory users = mock(UserDirectory.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        WikiPageService wiki = mock(WikiPageService.class);
        TaskService tasks = mock(TaskService.class);
        RagSourceService service = new RagSourceService(users, projects, wiki, tasks);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(userId, false);
        doThrow(new ForbiddenException("access denied")).when(projects).requireAccess(projectId, actor);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(projectId, userId, false))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(wiki, tasks);
    }

    @Test
    void listHidesUnknownInternalActorAndDoesNotCheckProject() {
        UserDirectory users = mock(UserDirectory.class);
        ProjectAccess projects = mock(ProjectAccess.class);
        WikiPageService wiki = mock(WikiPageService.class);
        TaskService tasks = mock(TaskService.class);
        RagSourceService service = new RagSourceService(users, projects, wiki, tasks);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("user missing")).when(users).requireUserExists(userId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.list(projectId, userId, false))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("The internal actor is not valid.");

        verifyNoInteractions(projects, wiki, tasks);
    }
}
