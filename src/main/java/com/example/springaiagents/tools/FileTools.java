package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** File tools exposed to sub-agents, sandboxed to the {@link AgentWorkspace} root. */
@Component
public class FileTools {

    private static final long MAX_READ_BYTES = 200_000;

    private final AgentWorkspace workspace;

    public FileTools(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(description = "Read a UTF-8 text file from the sandboxed agent workspace, given a path relative to the workspace root.")
    public String readFile(String relativePath) {
        try {
            Path path = workspace.resolve(relativePath);
            if (!Files.isRegularFile(path)) {
                return "No such file: " + relativePath;
            }
            if (Files.size(path) > MAX_READ_BYTES) {
                return "File too large to read (" + Files.size(path) + " bytes): " + relativePath;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (SecurityException e) {
            return "Rejected: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to read file: " + e.getMessage();
        }
    }

    @Tool(description = "Write UTF-8 text content to a file in the sandboxed agent workspace, creating parent directories as needed. Overwrites any existing file.")
    public String writeFile(String relativePath, String content) {
        try {
            Path path = workspace.resolve(relativePath);
            Files.createDirectories(path.getParent());
            String body = content == null ? "" : content;
            Files.writeString(path, body, StandardCharsets.UTF_8);
            return "Wrote " + body.getBytes(StandardCharsets.UTF_8).length + " bytes to " + relativePath;
        } catch (SecurityException e) {
            return "Rejected: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to write file: " + e.getMessage();
        }
    }

    @Tool(description = "List the files and directories immediately inside a directory in the sandboxed agent workspace, given a path relative to the workspace root (use '' for the root).")
    public String listFiles(String relativePath) {
        try {
            Path path = workspace.resolve(relativePath);
            StringBuilder result = new StringBuilder();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    result.append(Files.isDirectory(entry) ? "[dir] " : "[file] ")
                            .append(workspace.root().relativize(entry))
                            .append('\n');
                }
            }
            return result.isEmpty() ? "(empty directory)" : result.toString();
        } catch (SecurityException e) {
            return "Rejected: " + e.getMessage();
        } catch (IOException e) {
            return "Failed to list directory: " + e.getMessage();
        }
    }
}
