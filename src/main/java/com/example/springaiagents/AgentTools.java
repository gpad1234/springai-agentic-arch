package com.example.springaiagents;

import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;

/** Tools exposed to Claude's tool-calling loop, invoked by the advisor chain. */
public class AgentTools {

    @Tool(description = "Get the current date and time on the server")
    public String getCurrentDateTime() {
        return LocalDateTime.now().toString();
    }
}
