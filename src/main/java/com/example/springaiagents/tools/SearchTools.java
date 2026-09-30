package com.example.springaiagents.tools;

import com.example.springaiagents.workspace.AgentWorkspace;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Read-only search tools (grep/glob) exposed to sub-agents, sandboxed to the workspace root. */
@Component
public class SearchTools {

    private static final int MAX_MATCHES = 50;

    private final AgentWorkspace workspace;

    public SearchTools(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(description = "Search text files in the sandboxed agent workspace for lines matching a regular expression. Returns up to 50 matches as 'path:line: content'.")
    public String grep(String pattern) {
        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Invalid regex: " + e.getMessage();
        }

        List<String> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspace.root())) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                if (matches.size() >= MAX_MATCHES) {
                    return;
                }
                try {
                    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
                        if (regex.matcher(lines.get(i)).find()) {
                            matches.add(workspace.root().relativize(path) + ":" + (i + 1) + ": " + lines.get(i).trim());
                        }
                    }
                } catch (IOException | UncheckedIOException ignored) {
                    // skip unreadable/binary files
                }
            });
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
        return matches.isEmpty() ? "No matches" : String.join("\n", matches);
    }

    @Tool(description = "Find files in the sandboxed agent workspace whose relative path matches a glob pattern, e.g. '**/*.java'. Returns up to 200 matching paths.")
    public String glob(String globPattern) {
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
        } catch (IllegalArgumentException e) {
            return "Invalid glob pattern: " + e.getMessage();
        }

        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspace.root())) {
            stream.filter(Files::isRegularFile)
                    .map(path -> workspace.root().relativize(path))
                    .filter(matcher::matches)
                    .limit(200)
                    .forEach(p -> results.add(p.toString()));
        } catch (IOException e) {
            return "Glob search failed: " + e.getMessage();
        }
        return results.isEmpty() ? "No matches" : String.join("\n", results);
    }
}
