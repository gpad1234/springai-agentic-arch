package com.example.springaiagents.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Sandboxes all agent file/shell/search tools to a single directory tree. */
@Component
public class AgentWorkspace {

    private final Path root;

    public AgentWorkspace(@Value("${agents.workspace.root:./agent-workspace}") String rootPath) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create agent workspace at " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /** Resolves a workspace-relative path, rejecting any attempt to escape the sandbox. */
    public Path resolve(String relativePath) {
        Path candidate = root.resolve(relativePath == null ? "" : relativePath).normalize();
        if (!candidate.equals(root) && !candidate.startsWith(root)) {
            throw new SecurityException("Path escapes the agent workspace: " + relativePath);
        }
        return candidate;
    }
}
