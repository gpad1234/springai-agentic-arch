package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellToolTest {

    @TempDir
    Path tempDir;

    private ShellTool shellTool;

    @BeforeEach
    void setUp() {
        shellTool = new ShellTool(new AgentWorkspace(tempDir.toString()));
    }

    @Test
    void runsAnAllowedCommand() {
        String output = shellTool.runCommand("echo", List.of("hello"));

        assertThat(output).contains("hello");
    }

    @Test
    void rejectsCommandsNotOnTheAllowList() {
        String output = shellTool.runCommand("rm", List.of("-rf", "."));

        assertThat(output).contains("Rejected").contains("rm");
    }
}
