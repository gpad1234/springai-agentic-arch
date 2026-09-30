package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolsTest {

    @TempDir
    Path tempDir;

    private FileTools fileTools;

    @BeforeEach
    void setUp() {
        fileTools = new FileTools(new AgentWorkspace(tempDir.toString()));
    }

    @Test
    void writesAndReadsBackAFile() {
        String writeResult = fileTools.writeFile("notes/todo.txt", "buy milk");
        assertThat(writeResult).contains("Wrote");

        assertThat(fileTools.readFile("notes/todo.txt")).isEqualTo("buy milk");
    }

    @Test
    void listsDirectoryEntries() {
        fileTools.writeFile("a.txt", "one");
        fileTools.writeFile("sub/b.txt", "two");

        String listing = fileTools.listFiles("");

        assertThat(listing).contains("[file] a.txt").contains("[dir] sub");
    }

    @Test
    void reportsMissingFilesInsteadOfThrowing() {
        assertThat(fileTools.readFile("missing.txt")).contains("No such file");
    }

    @Test
    void rejectsPathTraversal() {
        assertThat(fileTools.readFile("../outside.txt")).contains("Rejected");
        assertThat(fileTools.writeFile("../outside.txt", "x")).contains("Rejected");
    }
}
