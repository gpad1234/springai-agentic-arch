package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SearchToolsTest {

    @TempDir
    Path tempDir;

    private SearchTools searchTools;

    @BeforeEach
    void setUp() {
        AgentWorkspace workspace = new AgentWorkspace(tempDir.toString());
        FileTools fileTools = new FileTools(workspace);
        fileTools.writeFile("src/Hello.java", "class Hello {\n  // TODO fix this\n}\n");
        fileTools.writeFile("README.md", "nothing interesting here\n");
        searchTools = new SearchTools(workspace);
    }

    @Test
    void grepFindsMatchingLines() {
        String result = searchTools.grep("TODO");

        assertThat(result).contains("Hello.java:2");
    }

    @Test
    void grepReportsNoMatches() {
        assertThat(searchTools.grep("NOT_PRESENT")).isEqualTo("No matches");
    }

    @Test
    void globFindsFilesByPattern() {
        String result = searchTools.glob("**/*.java");

        assertThat(result).contains("Hello.java").doesNotContain("README.md");
    }
}
