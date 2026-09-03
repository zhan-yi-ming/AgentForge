package com.agentforge.core.wiki.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ConflictException;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.wiki.domain.WikiPage;
import com.agentforge.core.wiki.domain.WikiPageRepository;

@Service
public class WikiPageService {

    private final WikiPageRepository wikiPages;
    private final ProjectAccess projectAccess;
    private final Clock clock;

    public WikiPageService(WikiPageRepository wikiPages, ProjectAccess projectAccess, Clock clock) {
        this.wikiPages = wikiPages;
        this.projectAccess = projectAccess;
        this.clock = clock;
    }

    @Transactional
    public WikiPageView create(UUID projectId, AuthenticatedActor actor, String title, String content) {
        projectAccess.requireAccess(projectId, actor);
        String normalizedTitle = title.trim();
        if (wikiPages.existsByProjectIdAndTitle(projectId, normalizedTitle)) {
            throw new ConflictException("A Wiki page with this title already exists in the project.");
        }
        try {
            return WikiPageView.from(wikiPages.save(WikiPage.create(
                    projectId,
                    normalizedTitle,
                    content,
                    Instant.now(clock))));
        }
        catch (DataIntegrityViolationException exception) {
            throw new ConflictException("The Wiki page conflicts with existing data.");
        }
    }

    @Transactional(readOnly = true)
    public WikiPageView get(UUID projectId, UUID wikiPageId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        return WikiPageView.from(find(projectId, wikiPageId));
    }

    @Transactional(readOnly = true)
    public List<WikiPageView> list(UUID projectId, AuthenticatedActor actor) {
        projectAccess.requireAccess(projectId, actor);
        return wikiPages.findAllByProjectIdOrderByUpdatedAtDesc(projectId)
                .stream()
                .map(WikiPageView::from)
                .toList();
    }

    @Transactional
    public WikiPageView update(
            UUID projectId,
            UUID wikiPageId,
            AuthenticatedActor actor,
            String title,
            String content,
            long expectedVersion) {
        projectAccess.requireAccess(projectId, actor);
        WikiPage page = find(projectId, wikiPageId);
        requireVersion(page, expectedVersion);
        String normalizedTitle = title.trim();
        if (wikiPages.existsByProjectIdAndTitleAndIdNot(projectId, normalizedTitle, wikiPageId)) {
            throw new ConflictException("A Wiki page with this title already exists in the project.");
        }
        page.update(normalizedTitle, content, Instant.now(clock));
        try {
            return WikiPageView.from(wikiPages.save(page));
        }
        catch (DataIntegrityViolationException | OptimisticLockingFailureException exception) {
            throw new ConflictException("The Wiki page was changed by another request.");
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID wikiPageId, AuthenticatedActor actor, long expectedVersion) {
        projectAccess.requireAccess(projectId, actor);
        WikiPage page = find(projectId, wikiPageId);
        requireVersion(page, expectedVersion);
        try {
            wikiPages.delete(page);
        }
        catch (OptimisticLockingFailureException exception) {
            throw new ConflictException("The Wiki page was changed by another request.");
        }
    }

    private WikiPage find(UUID projectId, UUID wikiPageId) {
        return wikiPages.findByProjectIdAndId(projectId, wikiPageId)
                .orElseThrow(() -> new ResourceNotFoundException("Wiki page not found: " + wikiPageId));
    }

    private void requireVersion(WikiPage page, long expectedVersion) {
        if (page.getVersion() != expectedVersion) {
            throw new ConflictException("The Wiki page version is stale.");
        }
    }
}
