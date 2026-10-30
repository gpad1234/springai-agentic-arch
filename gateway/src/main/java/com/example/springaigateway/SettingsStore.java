package com.example.springaigateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runtime-editable gateway settings, persisted to {@code <data-dir>/settings.json}. */
@Component
public class SettingsStore {

    public static final int MAX_RATE_LIMIT = 100_000;

    public record Settings(String backendUrl, int rateLimitPerMinute) {
    }

    private final ObjectMapper mapper;
    private final Path file;
    private volatile Settings current;

    public SettingsStore(ObjectMapper mapper, @Value("${gateway.data-dir}") String dataDir,
            @Value("${gateway.backend-url}") String defaultBackendUrl,
            @Value("${gateway.default-rate-limit-per-minute:0}") int defaultRateLimit) {
        this.mapper = mapper;
        this.file = Path.of(dataDir).resolve("settings.json");
        Settings loaded = null;
        if (Files.exists(file)) {
            try {
                Settings stored = mapper.readValue(file.toFile(), Settings.class);
                loaded = validate(stored.backendUrl(), stored.rateLimitPerMinute());
            }
            catch (IOException | IllegalArgumentException ex) {
                // Fall back to defaults if the file is unreadable or invalid.
            }
        }
        this.current = loaded != null ? loaded : validate(defaultBackendUrl, defaultRateLimit);
    }

    public Settings get() {
        return current;
    }

    public synchronized Settings update(String backendUrl, int rateLimitPerMinute) {
        Settings updated = validate(backendUrl, rateLimitPerMinute);
        try {
            SecureFiles.write(file, mapper.writeValueAsString(updated));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.current = updated;
        return updated;
    }

    static Settings validate(String backendUrl, int rateLimitPerMinute) {
        if (rateLimitPerMinute < 0 || rateLimitPerMinute > MAX_RATE_LIMIT) {
            throw new IllegalArgumentException("Rate limit must be between 0 and " + MAX_RATE_LIMIT);
        }
        return new Settings(normalizeBackendUrl(backendUrl), rateLimitPerMinute);
    }

    static String normalizeBackendUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Backend URL must not be blank");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Backend URL is not valid");
        }
        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        boolean plain = uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"));
        if (!httpScheme || uri.getHost() == null || !plain) {
            throw new IllegalArgumentException(
                    "Backend URL must look like http(s)://host[:port] with no path, query or credentials");
        }
        return scheme.toLowerCase() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}
