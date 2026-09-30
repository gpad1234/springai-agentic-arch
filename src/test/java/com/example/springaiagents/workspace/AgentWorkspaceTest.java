package com.example.springaiagents.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPathsInsideTheWorkspace() {
        AgentWorkspace workspace = new AgentWorkspace(tempDir.toString());

        Path resolved = workspace.resolve("notes/todo.txt");

        assertEquals(tempDir.resolve("notes/todo.txt").normalize(), resolved);
    }

    @Test
    void rejectsPathsThatEscapeTheWorkspace() {
        AgentWorkspace workspace = new AgentWorkspace(tempDir.toString());

        assertThrows(SecurityException.class, () -> workspace.resolve("../outside.txt"));
    }
}
