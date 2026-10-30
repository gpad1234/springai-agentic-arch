package com.example.springaigateway;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

/** Bounded in-memory log of recent proxied requests (method, path, status, duration only). */
@Component
public class RequestLog {

    public record Entry(String time, String method, String path, int status, long durationMs, String client) {
    }

    private static final int CAPACITY = 200;

    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(String method, String path, int status, long durationMs, String client) {
        if (entries.size() >= CAPACITY) {
            entries.removeLast();
        }
        entries.addFirst(new Entry(Instant.now().toString(), method, path, status, durationMs, client));
    }

    public synchronized List<Entry> recent() {
        return List.copyOf(entries);
    }
}
