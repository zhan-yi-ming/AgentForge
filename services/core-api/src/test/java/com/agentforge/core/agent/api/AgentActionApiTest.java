package com.agentforge.core.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.agentforge.core.agent.application.AgentActionService;
import com.agentforge.core.agent.application.AgentActionView;
import com.agentforge.core.agent.domain.AgentActionStatus;
import com.agentforge.core.agent.domain.AgentActionType;
import com.agentforge.core.security.SecurityConfiguration;
import com.agentforge.core.security.SecurityProblemWriter;
import com.agentforge.core.shared.web.RequestIdFilter;
import com.agentforge.core.task.application.TaskView;
import com.agentforge.core.task.domain.TaskPriority;
import com.agentforge.core.task.domain.TaskStatus;

@WebMvcTest(AgentActionController.class)
@Import({SecurityConfiguration.class, SecurityProblemWriter.class, RequestIdFilter.class})
@TestPropertySource(properties = {
    "agentforge.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "agentforge.security.jwt.issuer=https://agentforge.test/core-api",
    "agentforge.security.jwt.ttl=PT30M"
})
class AgentActionApiTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AgentActionService actionService;

    @Test
    void confirmReturnsExecutedActionAndTask() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        TaskView task = new TaskView(
                taskId, projectId, "Add login", null, TaskStatus.TODO, TaskPriority.HIGH, 0, now, now);
        when(actionService.confirm(org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.eq(actionId), any()))
                .thenReturn(new AgentActionView(
                        actionId, projectId, UUID.randomUUID(), AgentActionType.CREATE_TASK,
                        AgentActionStatus.EXECUTED, null, null, "Add login", null,
                        "TODO", "HIGH", task, now, now));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/actions/{actionId}/confirm", projectId, actionId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.resultTask.id").value(taskId.toString()));
    }

    @Test
    void rejectReturnsRejectedWithoutTask() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        when(actionService.reject(org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.eq(actionId), any()))
                .thenReturn(new AgentActionView(
                        actionId, projectId, UUID.randomUUID(), AgentActionType.CREATE_TASK,
                        AgentActionStatus.REJECTED, null, null, "Add login", null,
                        "TODO", "HIGH", null, now, now));

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent/actions/{actionId}/reject", projectId, actionId)
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).claim("roles", List.of("USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resultTask").doesNotExist());
    }
}
