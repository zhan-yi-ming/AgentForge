package com.agentforge.core.rag.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.agentforge.core.project.ProjectAccess;
import com.agentforge.core.security.AuthenticatedActor;
import com.agentforge.core.shared.error.ResourceNotFoundException;
import com.agentforge.core.shared.error.UnauthorizedException;
import com.agentforge.core.task.application.TaskService;
import com.agentforge.core.user.UserDirectory;
import com.agentforge.core.wiki.application.WikiPageService;

@Service
public class RagSourceService {

    private final UserDirectory userDirectory;
    private final ProjectAccess projectAccess;
    private final WikiPageService wikiPageService;
    private final TaskService taskService;

    public RagSourceService(
            UserDirectory userDirectory,
            ProjectAccess projectAccess,
            WikiPageService wikiPageService,
            TaskService taskService) {
        this.userDirectory = userDirectory;
        this.projectAccess = projectAccess;
        this.wikiPageService = wikiPageService;
        this.taskService = taskService;
    }

    public List<RagSource> list(UUID projectId, UUID userId, boolean actorAdmin) {
        try {
            userDirectory.requireUserExists(userId);
        }
        catch (ResourceNotFoundException exception) {
            throw new UnauthorizedException("The internal actor is not valid.");
        }
        AuthenticatedActor actor = new AuthenticatedActor(userId, actorAdmin);
        projectAccess.requireAccess(projectId, actor);

        List<RagSource> sources = new ArrayList<>();
        wikiPageService.list(projectId, actor).forEach(page -> sources.add(new RagSource(
                "WIKI",
                page.id(),
                page.version(),
                page.title(),
                page.content())));
        taskService.list(projectId, actor).forEach(task -> sources.add(new RagSource(
                "TASK",
                task.id(),
                task.version(),
                task.title(),
                taskContent(task.title(), task.status().name(), task.priority().name(), task.description()))));
        return List.copyOf(sources);
    }

    private String taskContent(String title, String status, String priority, String description) {
        StringBuilder content = new StringBuilder()
                .append("Title: ").append(title)
                .append("\nStatus: ").append(status)
                .append("\nPriority: ").append(priority);
        if (description != null) {
            content.append("\nDescription: ").append(description);
        }
        return content.toString();
    }
}
