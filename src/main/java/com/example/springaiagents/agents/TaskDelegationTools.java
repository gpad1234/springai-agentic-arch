package com.example.springaiagents.agents;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** The orchestrator's "Task" tool: lets the model act as its own router, delegating to a sub-agent. */
@Component
public class TaskDelegationTools {

    private final SubAgentService subAgentService;

    public TaskDelegationTools(SubAgentService subAgentService) {
        this.subAgentService = subAgentService;
    }

    @Tool(description = "Delegate a task to an isolated specialized sub-agent. Use agentType 'research' "
            + "for search/information gathering (read-only), or 'code' for file reads/writes and shell "
            + "commands inside the sandboxed workspace. Returns the sub-agent's final result.")
    public String delegateToSubAgent(String agentType, String task) {
        try {
            SubAgentType type = SubAgentType.fromString(agentType);
            return subAgentService.run(type, task);
        } catch (IllegalArgumentException e) {
            return "Delegation failed: " + e.getMessage();
        }
    }
}
