package com.example.springaigateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Activity history of proxied requests (metadata only: never bodies, query strings or keys). Entries are appended
 * to {@code <data-dir>/requests.jsonl}, replayed on startup and pruned to the configured retention.
 */
@Component
public class RequestLog {

    public record Entry(String time, String method, String path, int status, long durationMs, String client,
            String user, String errorCode) {
    }

    private static final int MAX_ENTRIES = 50_000;

    private final ObjectMapper mapper;
    private final Path file;
    private final SettingsStore settings;
    private final List<Entry> entries = new ArrayList<>();

    public RequestLog(ObjectMapper mapper, @Value("${gateway.data-dir}") String dataDir, SettingsStore settings) {
        this.mapper = mapper;
        this.file = Path.of(dataDir).resolve("requests.jsonl");
        this.settings = settings;
        load();
    }

    public synchronized void add(Entry entry) {
        entries.add(entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(0, entries.size() - MAX_ENTRIES).clear();
        }
        try {
            if (!Files.exists(file)) {
                SecureFiles.write(file, "");
            }
            Files.writeString(file, mapper.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        }
        catch (IOException ex) {
            // History is best-effort; never fail a proxied request because the log can't be written.
        }
    }

    /** Newest first, optionally filtered; {@code from} is inclusive and {@code to} exclusive. */
    public synchronized List<Entry> query(String user, String statusClass, Instant from, Instant to, int limit) {
        List<Entry> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && result.size() < limit; i--) {
            Entry e = entries.get(i);
            if (matches(e, user, statusClass, from, to)) {
                result.add(e);
            }
        }
        return result;
    }

    /** All entries in the time range, oldest first, for aggregation. */
    public synchronized List<Entry> between(Instant from, Instant to) {
        return entries.stream().filter(e -> matches(e, null, null, from, to)).toList();
    }

    private static boolean matches(Entry e, String user, String statusClass, Instant from, Instant to) {
        if (user != null && !user.isEmpty() && !user.equals(e.user() == null ? "" : e.user())) {
            return false;
        }
        if (statusClass != null && !statusClass.isEmpty() && !statusMatches(e.status(), statusClass)) {
            return false;
        }
        Instant time = Instant.parse(e.time());
        return (from == null || !time.isBefore(from)) && (to == null || time.isBefore(to));
    }

    private static boolean statusMatches(int status, String statusClass) {
        return switch (statusClass) {
            case "ok" -> status >= 200 && status < 400;
            case "client" -> status >= 400 && status < 500;
            case "error" -> status >= 500 || status == 0;
            default -> true;
        };
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        Instant cutoff = Instant.now().minus(settings.get().historyRetentionDays(), ChronoUnit.DAYS);
        boolean dropped = false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                try {
                    Entry e = mapper.readValue(line, Entry.class);
                    if (Instant.parse(e.time()).isBefore(cutoff)) {
                        dropped = true;
                    }
                    else {
                        entries.add(e);
                    }
                }
                catch (IOException | RuntimeException ex) {
                    dropped = true; // truncated or corrupt line
                }
            }
            if (entries.size() > MAX_ENTRIES) {
                entries.subList(0, entries.size() - MAX_ENTRIES).clear();
                dropped = true;
            }
            if (dropped) {
                StringBuilder sb = new StringBuilder();
                for (Entry e : entries) {
                    sb.append(mapper.writeValueAsString(e)).append('\n');
                }
                SecureFiles.write(file, sb.toString());
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + file, ex);
        }
    }
}
