package com.agentforge.core.wiki.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.wiki.domain.WikiPage;
import com.agentforge.core.wiki.domain.WikiPageRepository;

@ExtendWith(MockitoExtension.class)
class WikiPageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private WikiPageRepository wikiPages;

    @Mock
    private ProjectAccess projectAccess;

    private WikiPageService service;

    @BeforeEach
    void setUp() {
        service = new WikiPageService(wikiPages, projectAccess, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createChecksProjectAccessAndNormalizesTitle() {
        UUID projectId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        when(wikiPages.existsByProjectIdAndTitle(projectId, "Architecture")).thenReturn(false);
        when(wikiPages.save(any(WikiPage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WikiPageView result = service.create(projectId, actor, "  Architecture  ", "# System");

        assertThat(result.title()).isEqualTo("Architecture");
        assertThat(result.content()).isEqualTo("# System");
        assertThat(result.version()).isZero();
        verify(projectAccess).requireAccess(projectId, actor);
    }

    @Test
    void updateRejectsStaleVersion() {
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        WikiPage page = WikiPage.create(projectId, "Architecture", "old", NOW);
        when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> service.update(
                projectId,
                pageId,
                new AuthenticatedActor(UUID.randomUUID(), true),
                "Architecture",
                "new",
                1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void updateAndDeleteUseCurrentVersion() {
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), false);
        WikiPage page = WikiPage.create(projectId, "Architecture", "old", NOW);
        when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.of(page));
        when(wikiPages.existsByProjectIdAndTitleAndIdNot(projectId, "Updated", pageId))
                .thenReturn(false);
        when(wikiPages.save(page)).thenReturn(page);

        WikiPageView updated = service.update(
                projectId,
                pageId,
                actor,
                " Updated ",
                "new",
                0);

        assertThat(updated.title()).isEqualTo("Updated");
        assertThat(updated.content()).isEqualTo("new");
        service.delete(projectId, pageId, actor, 0);
        verify(wikiPages).delete(page);
    }

    @Test
    void getRequiresResourceToBelongToPathProject() {
        UUID projectId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        when(wikiPages.findByProjectIdAndId(projectId, pageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(
                projectId,
                pageId,
                new AuthenticatedActor(UUID.randomUUID(), false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsRepositoryOrder() {
        UUID projectId = UUID.randomUUID();
        WikiPage first = WikiPage.create(projectId, "First", "one", NOW);
        WikiPage second = WikiPage.create(projectId, "Second", "two", NOW.minusSeconds(60));
        when(wikiPages.findAllByProjectIdOrderByUpdatedAtDesc(projectId)).thenReturn(List.of(first, second));

        assertThat(service.list(projectId, new AuthenticatedActor(UUID.randomUUID(), true)))
                .extracting(WikiPageView::title)
                .containsExactly("First", "Second");
    }
}
