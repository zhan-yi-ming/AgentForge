package com.agentforge.core.wiki.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.wiki.application.WikiPageService;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/wiki-pages")
public class WikiPageController {

    private final WikiPageService wikiPageService;

    public WikiPageController(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @PostMapping
    ResponseEntity<WikiPageResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateWikiPageRequest request) {
        WikiPageResponse response = WikiPageResponse.from(wikiPageService.create(
                projectId,
                AuthenticatedActor.from(jwt),
                request.title(),
                request.content()));
        return ResponseEntity
                .created(URI.create("/api/v1/projects/" + projectId + "/wiki-pages/" + response.id()))
                .body(response);
    }

    @GetMapping
    List<WikiPageResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return wikiPageService.list(projectId, AuthenticatedActor.from(jwt))
                .stream()
                .map(WikiPageResponse::from)
                .toList();
    }

    @GetMapping("/{wikiPageId}")
    WikiPageResponse get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID wikiPageId) {
        return WikiPageResponse.from(wikiPageService.get(
                projectId,
                wikiPageId,
                AuthenticatedActor.from(jwt)));
    }

    @PutMapping("/{wikiPageId}")
    WikiPageResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID wikiPageId,
            @Valid @RequestBody UpdateWikiPageRequest request) {
        return WikiPageResponse.from(wikiPageService.update(
                projectId,
                wikiPageId,
                AuthenticatedActor.from(jwt),
                request.title(),
                request.content(),
                request.version()));
    }

    @DeleteMapping("/{wikiPageId}")
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID wikiPageId,
            @RequestParam @PositiveOrZero long version) {
        wikiPageService.delete(projectId, wikiPageId, AuthenticatedActor.from(jwt), version);
        return ResponseEntity.noContent().build();
    }
}
