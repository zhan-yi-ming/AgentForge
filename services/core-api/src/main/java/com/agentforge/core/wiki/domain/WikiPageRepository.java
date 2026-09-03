package com.agentforge.core.wiki.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WikiPageRepository {

    WikiPage save(WikiPage wikiPage);

    Optional<WikiPage> findByProjectIdAndId(UUID projectId, UUID id);

    List<WikiPage> findAllByProjectIdOrderByUpdatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndTitle(UUID projectId, String title);

    boolean existsByProjectIdAndTitleAndIdNot(UUID projectId, String title, UUID id);

    void delete(WikiPage wikiPage);
}
