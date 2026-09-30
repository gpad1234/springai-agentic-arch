package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Restricted shell execution exposed to sub-agents: an allow-list of read-only
 * commands, run as a direct process (no shell interpretation) inside the sandboxed
 * agent workspace, to avoid command injection and destructive operations.
 */
@Component
public class ShellTool {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "ls", "pwd", "cat", "wc", "grep", "find", "echo", "date");
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT_CHARS = 4000;

    private final AgentWorkspace workspace;

    public ShellTool(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(description = "Run a safe, read-only shell command (ls, pwd, cat, wc, grep, find, echo, date) inside the sandboxed agent workspace. Arguments are passed literally with no shell interpretation.")
    public String runCommand(String command, List<String> args) {
        if (command == null || !ALLOWED_COMMANDS.contains(command)) {
            return "Rejected: command '" + command + "' is not in the allowed list " + ALLOWED_COMMANDS;
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }

        try {
            Process process = new ProcessBuilder(commandLine)
                    .directory(workspace.root().toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + TIMEOUT_SECONDS + "s";
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = output.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
            }
            return output.isBlank() ? "(no output, exit code " + process.exitValue() + ")" : output;
        } catch (IOException e) {
            return "Failed to run command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Command interrupted";
        }
    }
}
