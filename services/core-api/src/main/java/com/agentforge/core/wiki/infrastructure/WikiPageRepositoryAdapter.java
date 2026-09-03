package com.agentforge.core.wiki.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.agentforge.core.wiki.domain.WikiPage;
import com.agentforge.core.wiki.domain.WikiPageRepository;

@Repository
class WikiPageRepositoryAdapter implements WikiPageRepository {

    private final SpringDataWikiPageRepository repository;

    WikiPageRepositoryAdapter(SpringDataWikiPageRepository repository) {
        this.repository = repository;
    }

    @Override
    public WikiPage save(WikiPage wikiPage) {
        return repository.saveAndFlush(wikiPage);
    }

    @Override
    public Optional<WikiPage> findByProjectIdAndId(UUID projectId, UUID id) {
        return repository.findByProjectIdAndId(projectId, id);
    }

    @Override
    public List<WikiPage> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId) {
        return repository.findAllByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    @Override
    public boolean existsByProjectIdAndTitle(UUID projectId, String title) {
        return repository.existsByProjectIdAndTitle(projectId, title);
    }

    @Override
    public boolean existsByProjectIdAndTitleAndIdNot(UUID projectId, String title, UUID id) {
        return repository.existsByProjectIdAndTitleAndIdNot(projectId, title, id);
    }

    @Override
    public void delete(WikiPage wikiPage) {
        repository.delete(wikiPage);
        repository.flush();
    }
}

interface SpringDataWikiPageRepository extends JpaRepository<WikiPage, UUID> {

    Optional<WikiPage> findByProjectIdAndId(UUID projectId, UUID id);

    List<WikiPage> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndTitle(UUID projectId, String title);

    boolean existsByProjectIdAndTitleAndIdNot(UUID projectId, String title, UUID id);
}
