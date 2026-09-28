package com.agentforge.core.graph.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agentforge.graph.enabled",havingValue="true")
public class GraphSyncScheduler {
    private final GraphSyncProcessor processor;
    private final boolean syncEnabled;
    public GraphSyncScheduler(GraphSyncProcessor processor,
        @org.springframework.beans.factory.annotation.Value("${agentforge.graph.sync.enabled:true}") boolean syncEnabled) {
        this.processor=processor; this.syncEnabled=syncEnabled;
    }
    @Scheduled(fixedDelayString="${agentforge.graph.sync-interval-ms:5000}")
    public void drain() {
        if (!syncEnabled) return;
        for (int i=0; i<20 && processor.processOne(); i++) { }
    }
}
