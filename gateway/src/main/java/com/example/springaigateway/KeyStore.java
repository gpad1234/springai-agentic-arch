package com.example.springaigateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores labelled Anthropic API keys in {@code <data-dir>/keys.json} and mirrors the active one to
 * {@code <data-dir>/active-key.env} so it can be sourced before (re)starting the backend.
 */
@Component
public class KeyStore {

    public record ApiKey(String id, String label, String key, String createdAt) {
    }

    public record KeyView(String id, String label, String masked, String createdAt, boolean active) {
    }

    record State(List<ApiKey> keys, String activeId) {
    }

    // Restricting the alphabet also guarantees the generated env file can't contain shell metacharacters.
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,256}");
    private static final int MAX_LABEL = 60;

    private final ObjectMapper mapper;
    private final Path keysFile;
    private final Path envFile;
    private State state;

    public KeyStore(ObjectMapper mapper, @Value("${gateway.data-dir}") String dataDir) {
        this.mapper = mapper;
        Path dir = Path.of(dataDir);
        this.keysFile = dir.resolve("keys.json");
        this.envFile = dir.resolve("active-key.env");
        State loaded = new State(new ArrayList<>(), null);
        if (Files.exists(keysFile)) {
            try {
                State stored = mapper.readValue(keysFile.toFile(), State.class);
                loaded = new State(new ArrayList<>(stored.keys()), stored.activeId());
            }
            catch (IOException ex) {
                throw new UncheckedIOException("Could not read " + keysFile, ex);
            }
        }
        this.state = loaded;
    }

    public Path activeKeyFile() {
        return envFile.toAbsolutePath();
    }

    public synchronized List<KeyView> list() {
        return state.keys().stream().map(this::view).toList();
    }

    public synchronized KeyView add(String label, String key) {
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty() || cleanLabel.length() > MAX_LABEL) {
            throw new IllegalArgumentException("Label is required (max " + MAX_LABEL + " characters)");
        }
        String cleanKey = key == null ? "" : key.trim();
        if (!KEY_PATTERN.matcher(cleanKey).matches()) {
            throw new IllegalArgumentException("Key must be 8-256 characters: letters, digits, '-' or '_'");
        }
        ApiKey entry = new ApiKey(UUID.randomUUID().toString(), cleanLabel, cleanKey, Instant.now().toString());
        State next = new State(new ArrayList<>(state.keys()), state.activeId());
        next.keys().add(entry);
        State saved = persist(new State(next.keys(), next.activeId() == null ? entry.id() : next.activeId()));
        this.state = saved;
        return view(entry);
    }

    public synchronized void activate(String id) {
        find(id);
        this.state = persist(new State(state.keys(), id));
    }

    public synchronized void remove(String id) {
        find(id);
        List<ApiKey> remaining = new ArrayList<>(state.keys());
        remaining.removeIf(k -> k.id().equals(id));
        String activeId = id.equals(state.activeId()) ? null : state.activeId();
        this.state = persist(new State(remaining, activeId));
    }

    private ApiKey find(String id) {
        return state.keys().stream().filter(k -> k.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown key id"));
    }

    private KeyView view(ApiKey key) {
        String tail = key.key().substring(Math.max(0, key.key().length() - 4));
        return new KeyView(key.id(), key.label(), "••••" + tail, key.createdAt(), key.id().equals(state.activeId()));
    }

    private State persist(State next) {
        try {
            SecureFiles.write(keysFile, mapper.writeValueAsString(next));
            ApiKey active = next.keys().stream().filter(k -> k.id().equals(next.activeId())).findFirst().orElse(null);
            if (active == null) {
                Files.deleteIfExists(envFile);
            }
            else {
                SecureFiles.write(envFile, "export ANTHROPIC_API_KEY=" + active.key() + "\n");
            }
            return next;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
