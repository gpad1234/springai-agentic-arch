package com.example.springaiagents.agents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentTypeTest {

    @Test
    void parsesKnownTypesCaseInsensitively() {
        assertThat(SubAgentType.fromString("research")).isEqualTo(SubAgentType.RESEARCH);
        assertThat(SubAgentType.fromString("CODE")).isEqualTo(SubAgentType.CODE);
    }

    @Test
    void rejectsUnknownTypes() {
        assertThatThrownBy(() -> SubAgentType.fromString("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown agentType");
    }
}
