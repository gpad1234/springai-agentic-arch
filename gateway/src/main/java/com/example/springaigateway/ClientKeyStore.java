package com.example.springaigateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pilot users: client keys ({@code gw_...}) that callers send as {@code X-API-Key}. Only a SHA-256 hash and the
 * last four characters are stored; the full key is shown once at creation. Persisted to {@code client-keys.json}.
 */
@Component
public class ClientKeyStore {

    public static final int MAX_LIMIT = 1_000_000;
    private static final int MAX_LABEL = 60;
    private static final int MAX_NOTE = 200;
    private static final Duration LAST_USED_PERSIST_INTERVAL = Duration.ofMinutes(1);

    public record Client(String id, String label, String note, String hash, String last4, String createdAt,
            String lastUsedAt, boolean revoked, int rateLimitPerMinute, int dailyCap) {
    }

    public record ClientView(String id, String label, String note, String masked, String createdAt, String lastUsedAt,
            boolean revoked, int rateLimitPerMinute, int dailyCap, int callsToday) {
    }

    public record Created(ClientView client, String key) {
    }

    public record Update(String label, String note, Integer rateLimitPerMinute, Integer dailyCap, Boolean revoked) {
    }

    record State(List<Client> clients) {
    }

    private record Window(long minute, int count) {
    }

    private record Day(LocalDate date, int count) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper;
    private final Path file;
    private final Map<String, Window> minuteWindows = new HashMap<>();
    private final Map<String, Day> days = new HashMap<>();
    private List<Client> clients;
    private Instant lastPersisted = Instant.EPOCH;

    public ClientKeyStore(ObjectMapper mapper, @Value("${gateway.data-dir}") String dataDir) {
        this.mapper = mapper;
        this.file = Path.of(dataDir).resolve("client-keys.json");
        List<Client> loaded = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                loaded.addAll(mapper.readValue(file.toFile(), State.class).clients());
            }
            catch (IOException ex) {
                throw new UncheckedIOException("Could not read " + file, ex);
            }
        }
        this.clients = loaded;
    }

    public synchronized List<ClientView> list() {
        return clients.stream().map(this::view).toList();
    }

    public synchronized Created create(String label, String note, Integer rateLimit, Integer dailyCap) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String key = "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Client client = new Client(UUID.randomUUID().toString(), cleanLabel(label), cleanNote(note), hash(key),
                key.substring(key.length() - 4), Instant.now().toString(), null, false,
                limit(rateLimit, "Per-minute limit"), limit(dailyCap, "Daily cap"));
        List<Client> next = new ArrayList<>(clients);
        next.add(client);
        persist(next);
        return new Created(view(client), key);
    }

    public synchronized ClientView update(String id, Update change) {
        Client old = find(id);
        Client updated = new Client(old.id(), change.label() == null ? old.label() : cleanLabel(change.label()),
                change.note() == null ? old.note() : cleanNote(change.note()), old.hash(), old.last4(),
                old.createdAt(), old.lastUsedAt(), change.revoked() == null ? old.revoked() : change.revoked(),
                change.rateLimitPerMinute() == null ? old.rateLimitPerMinute()
                        : limit(change.rateLimitPerMinute(), "Per-minute limit"),
                change.dailyCap() == null ? old.dailyCap() : limit(change.dailyCap(), "Daily cap"));
        persist(replace(updated));
        return view(updated);
    }

    public synchronized void delete(String id) {
        find(id);
        List<Client> next = new ArrayList<>(clients);
        next.removeIf(c -> c.id().equals(id));
        persist(next);
        minuteWindows.remove(id);
        days.remove(id);
    }

    /** Looks up a presented key, comparing hashes in constant time. Revoked keys never match. */
    public synchronized Optional<Client> authenticate(String presented) {
        if (presented == null || presented.isBlank() || presented.length() > 256) {
            return Optional.empty();
        }
        byte[] candidate = hash(presented.trim()).getBytes(StandardCharsets.UTF_8);
        Client match = null;
        for (Client c : clients) {
            if (MessageDigest.isEqual(candidate, c.hash().getBytes(StandardCharsets.UTF_8))) {
                match = c;
            }
        }
        return match == null || match.revoked() ? Optional.empty() : Optional.of(match);
    }

    /** Counts one call against the user's limits; returns an error code when a limit is hit, otherwise null. */
    public synchronized String consume(Client client, Instant now, LocalDate today) {
        if (client.dailyCap() > 0) {
            Day day = days.get(client.id());
            if (day != null && day.date().equals(today) && day.count() >= client.dailyCap()) {
                return "daily_cap_reached";
            }
        }
        if (client.rateLimitPerMinute() > 0) {
            long minute = now.toEpochMilli() / 60_000;
            Window w = minuteWindows.get(client.id());
            int count = w != null && w.minute() == minute ? w.count() : 0;
            if (count >= client.rateLimitPerMinute()) {
                return "rate_limited";
            }
            minuteWindows.put(client.id(), new Window(minute, count + 1));
        }
        Day day = days.get(client.id());
        days.put(client.id(), new Day(today, day != null && day.date().equals(today) ? day.count() + 1 : 1));
        touch(client, now);
        return null;
    }

    private void touch(Client client, Instant now) {
        Client updated = new Client(client.id(), client.label(), client.note(), client.hash(), client.last4(),
                client.createdAt(), now.toString(), client.revoked(), client.rateLimitPerMinute(), client.dailyCap());
        List<Client> next = replace(updated);
        if (Duration.between(lastPersisted, now).compareTo(LAST_USED_PERSIST_INTERVAL) >= 0) {
            try {
                persist(next);
                lastPersisted = now;
            }
            catch (UncheckedIOException ex) {
                this.clients = next;
            }
        }
        else {
            this.clients = next;
        }
    }

    private List<Client> replace(Client updated) {
        List<Client> next = new ArrayList<>(clients);
        next.replaceAll(c -> c.id().equals(updated.id()) ? updated : c);
        return next;
    }

    private Client find(String id) {
        return clients.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown user id"));
    }

    private ClientView view(Client c) {
        Day day = days.get(c.id());
        int today = day != null && day.date().equals(LocalDate.now()) ? day.count() : 0;
        return new ClientView(c.id(), c.label(), c.note(), "gw_••••" + c.last4(), c.createdAt(), c.lastUsedAt(),
                c.revoked(), c.rateLimitPerMinute(), c.dailyCap(), today);
    }

    private void persist(List<Client> next) {
        try {
            SecureFiles.write(file, mapper.writeValueAsString(new State(next)));
            this.clients = next;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String cleanLabel(String label) {
        String clean = label == null ? "" : label.trim();
        if (clean.isEmpty() || clean.length() > MAX_LABEL) {
            throw new IllegalArgumentException("Name is required (max " + MAX_LABEL + " characters)");
        }
        return clean;
    }

    private static String cleanNote(String note) {
        String clean = note == null ? "" : note.trim();
        if (clean.length() > MAX_NOTE) {
            throw new IllegalArgumentException("Note is too long (max " + MAX_NOTE + " characters)");
        }
        return clean;
    }

    private static int limit(Integer value, String name) {
        int v = value == null ? 0 : value;
        if (v < 0 || v > MAX_LIMIT) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_LIMIT + " (0 = unlimited)");
        }
        return v;
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
