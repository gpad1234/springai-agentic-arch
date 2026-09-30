package com.example.springaiagents.agents;

import java.util.Arrays;
import java.util.Locale;

/** The specialized sub-agents an orchestrator can delegate a task to. */
public enum SubAgentType {

    RESEARCH,
    CODE;

    static SubAgentType fromString(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown agentType '" + value + "', expected one of "
                                + Arrays.toString(values())));
    }
}
