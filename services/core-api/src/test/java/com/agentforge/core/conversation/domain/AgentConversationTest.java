package com.agentforge.core.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AgentConversationTest {

    @Test
    void previewTruncationKeepsTheLastUnicodeCodePointIntact() {
        AgentConversation conversation = AgentConversation.start(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(239) + "😀" + "tail", Instant.EPOCH);

        assertThat(conversation.getPreview()).isEqualTo("a".repeat(239) + "😀");
        assertThat(conversation.getPreview().codePointCount(0, conversation.getPreview().length())).isEqualTo(240);
    }
}
