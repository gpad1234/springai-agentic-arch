package com.example.springaigateway;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class AdminController {

    public record Status(String backendUrl, boolean reachable, Integer httpStatus, Long latencyMs,
            int rateLimitPerMinute, String activeKeyLabel, boolean requireClientKey, int activeClients, boolean paused) {
    }

    public record KeysResponse(List<KeyStore.KeyView> keys, String activeKeyFile) {
    }

    public record SettingsRequest(String backendUrl, Integer rateLimitPerMinute, Boolean requireClientKey,
            Integer historyRetentionDays, Boolean paused) {
    }

    public record NewClientRequest(String label, String note, Integer rateLimitPerMinute, Integer dailyCap) {
    }

    public record UsageRow(String user, long calls, long errors, long limited, long unauthorized, long p50Ms,
            long p95Ms, String firstSeen, String lastSeen) {
    }

    public record UsageResponse(String from, String to, List<UsageRow> rows) {
    }

    public record NewKeyRequest(String label, String key) {
    }

    private final SettingsStore settings;
    private final KeyStore keys;
    private final RequestLog requestLog;
    private final ClientKeyStore clients;

    public AdminController(SettingsStore settings, KeyStore keys, RequestLog requestLog, ClientKeyStore clients) {
        this.settings = settings;
        this.keys = keys;
        this.requestLog = requestLog;
        this.clients = clients;
    }

    @GetMapping({ "/admin", "/admin/" })
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin/index.html").build();
    }

    @GetMapping("/admin/status")
    public Mono<Status> status() {
        SettingsStore.Settings current = settings.get();
        String activeLabel = keys.list().stream().filter(KeyStore.KeyView::active)
                .map(KeyStore.KeyView::label).findFirst().orElse(null);
        boolean require = current.requireClientKey();
        boolean paused = current.paused();
        int activeClients = (int) clients.list().stream().filter(c -> !c.revoked()).count();
        long start = System.nanoTime();
        return WebClient.create(current.backendUrl()).get().uri("/")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .timeout(Duration.ofSeconds(2))
                .map(code -> new Status(current.backendUrl(), true, code, (System.nanoTime() - start) / 1_000_000,
                        current.rateLimitPerMinute(), activeLabel, require, activeClients, paused))
                .onErrorReturn(new Status(current.backendUrl(), false, null, null, current.rateLimitPerMinute(),
                        activeLabel, require, activeClients, paused));
    }

    @GetMapping("/admin/settings")
    public SettingsStore.Settings getSettings() {
        return settings.get();
    }

    @PutMapping("/admin/settings")
    public SettingsStore.Settings putSettings(@RequestBody SettingsRequest request) {
        SettingsStore.Settings current = settings.get();
        return settings.update(request.backendUrl() == null ? current.backendUrl() : request.backendUrl(),
                request.rateLimitPerMinute() == null ? current.rateLimitPerMinute() : request.rateLimitPerMinute(),
                request.requireClientKey(), request.historyRetentionDays(), request.paused());
    }

    @GetMapping("/admin/keys")
    public KeysResponse listKeys() {
        return new KeysResponse(keys.list(), keys.activeKeyFile().toString());
    }

    @PostMapping("/admin/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyStore.KeyView addKey(@RequestBody NewKeyRequest request) {
        return keys.add(request.label(), request.key());
    }

    @PostMapping("/admin/keys/{id}/activate")
    public KeysResponse activate(@PathVariable String id) {
        keys.activate(id);
        return listKeys();
    }

    @DeleteMapping("/admin/keys/{id}")
    public KeysResponse delete(@PathVariable String id) {
        keys.remove(id);
        return listKeys();
    }

    @GetMapping("/admin/client-keys")
    public List<ClientKeyStore.ClientView> listClients() {
        return clients.list();
    }

    @PostMapping("/admin/client-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ClientKeyStore.Created createClient(@RequestBody NewClientRequest request) {
        return clients.create(request.label(), request.note(), request.rateLimitPerMinute(), request.dailyCap());
    }

    @PutMapping("/admin/client-keys/{id}")
    public ClientKeyStore.ClientView updateClient(@PathVariable String id,
            @RequestBody ClientKeyStore.Update request) {
        return clients.update(id, request);
    }

    @DeleteMapping("/admin/client-keys/{id}")
    public List<ClientKeyStore.ClientView> deleteClient(@PathVariable String id) {
        clients.delete(id);
        return clients.list();
    }

    /** Recent activity, newest first. {@code status} is ok, client or error; dates are yyyy-MM-dd (local). */
    @GetMapping("/admin/requests")
    public List<RequestLog.Entry> requests(@RequestParam(required = false) String user,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "200") int limit) {
        return requestLog.query(user, status, dayStart(from, null), dayEnd(to), Math.max(1, Math.min(limit, 1000)));
    }

    public record DayCount(String date, long calls, long errors) {
    }

    public record Overview(long callsToday, long errorsToday, long totalCalls, long successfulCalls, int activeUsers,
            double errorRate, List<DayCount> days) {
    }

    @GetMapping("/admin/overview")
    public Overview overview() {
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        List<RequestLog.Entry> week = requestLog.between(today.minusDays(6).atStartOfDay(zone).toInstant(), null);
        Map<LocalDate, long[]> perDay = new TreeMap<>();
        for (int i = 6; i >= 0; i--) {
            perDay.put(today.minusDays(i), new long[2]);
        }
        long calls = 0, errors = 0, ok = 0;
        for (RequestLog.Entry e : week) {
            long[] d = perDay.get(Instant.parse(e.time()).atZone(zone).toLocalDate());
            boolean error = e.status() >= 500 || e.status() == 0;
            d[0]++;
            d[1] += error ? 1 : 0;
            calls++;
            errors += error ? 1 : 0;
            ok += e.status() >= 200 && e.status() < 400 ? 1 : 0;
        }
        long[] t = perDay.get(today);
        int active = (int) clients.list().stream().filter(c -> !c.revoked()).count();
        List<DayCount> days = new ArrayList<>();
        perDay.forEach((d, v) -> days.add(new DayCount(d.toString(), v[0], v[1])));
        return new Overview(t[0], t[1], calls, ok, active, calls == 0 ? 0 : (double) errors / calls, days);
    }

    /** Printable one-page pilot summary (open in the browser, then print or save as PDF). */
    @GetMapping(value = "/admin/report", produces = "text/html")
    public ResponseEntity<String> report(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UsageResponse usage = usage(from, to);
        long calls = usage.rows().stream().mapToLong(UsageRow::calls).sum();
        long errors = usage.rows().stream().mapToLong(UsageRow::errors).sum();
        StringBuilder h = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Pilot report</title>"
                + "<style>body{font:14px system-ui,sans-serif;max-width:820px;margin:32px auto;color:#1f1d1a}"
                + "table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #ddd}"
                + ".k{display:inline-block;margin:0 24px 12px 0}.k b{display:block;font-size:22px}"
                + "@media print{button{display:none}}</style></head><body><h1>AI gateway pilot report</h1><p>Period: ")
                .append(esc(usage.from())).append(" to ").append(esc(usage.to())).append(" &middot; generated ")
                .append(esc(Instant.now().toString())).append("</p><button onclick=\"print()\">Print / save as PDF</button><p>")
                .append("<span class=k><b>").append(calls).append("</b>calls</span>")
                .append("<span class=k><b>").append(usage.rows().stream().filter(r -> !r.user().equals("(no key)")).count())
                .append("</b>active users</span>")
                .append("<span class=k><b>").append(calls == 0 ? "0" : String.format("%.1f", 100.0 * errors / calls))
                .append("%</b>server error rate</span></p><table><tr><th>User</th><th>Calls</th><th>Errors</th>"
                        + "<th>Rate-limited</th><th>p50 ms</th><th>p95 ms</th><th>Last seen</th></tr>");
        for (UsageRow r : usage.rows()) {
            h.append("<tr><td>").append(esc(r.user())).append("</td><td>").append(r.calls()).append("</td><td>")
                    .append(r.errors()).append("</td><td>").append(r.limited()).append("</td><td>").append(r.p50Ms())
                    .append("</td><td>").append(r.p95Ms()).append("</td><td>").append(esc(r.lastSeen())).append("</td></tr>");
        }
        h.append("</table><p style=\"color:#777\">Token counts and cost are not included: the backend does not report them yet.</p></body></html>");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(h.toString());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public record TestCallRequest(String prompt) {
    }

    public record TestCallResult(int status, String body, long durationMs) {
    }

    /** Sends a prompt to the current backend from the admin UI; recorded in Activity as "(admin test)". */
    @PostMapping("/admin/test-call")
    public Mono<TestCallResult> testCall(@RequestBody TestCallRequest request) {
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isEmpty() || prompt.length() > 4000) {
            throw new IllegalArgumentException("Prompt is required (max 4000 characters)");
        }
        long start = System.nanoTime();
        return WebClient.create(settings.get().backendUrl()).post().uri("/api/claude/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", prompt, "conversationId", "admin-test"))
                .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new TestCallResult(r.statusCode().value(), b, ms(start))))
                .timeout(Duration.ofSeconds(120))
                .onErrorResume(ex -> Mono.just(new TestCallResult(0, "Could not reach the backend: "
                        + ex.getClass().getSimpleName(), ms(start))))
                .doOnNext(r -> requestLog.add(new RequestLog.Entry(Instant.now().toString(), "POST",
                        "/api/claude/chat", r.status() == 0 ? 502 : r.status(), r.durationMs(), "admin-ui",
                        "(admin test)", r.status() == 0 ? "backend_unreachable" : null)));
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @GetMapping("/admin/usage")
    public UsageResponse usage(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = from == null || from.isBlank() ? today.minusDays(6) : parseDate(from);
        LocalDate toDate = to == null || to.isBlank() ? today : parseDate(to);
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        return new UsageResponse(fromDate.toString(), toDate.toString(),
                aggregate(requestLog.between(dayStart(fromDate.toString(), null), dayEnd(toDate.toString()))));
    }

    @GetMapping(value = "/admin/usage.csv", produces = "text/csv")
    public ResponseEntity<String> usageCsv(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UsageResponse usage = usage(from, to);
        StringBuilder csv = new StringBuilder("user,calls,errors,rate_limited,unauthorized,p50_ms,p95_ms,first_seen,last_seen\n");
        for (UsageRow r : usage.rows()) {
            csv.append(csvCell(r.user())).append(',').append(r.calls()).append(',').append(r.errors()).append(',')
                    .append(r.limited()).append(',').append(r.unauthorized()).append(',').append(r.p50Ms()).append(',')
                    .append(r.p95Ms()).append(',').append(r.firstSeen()).append(',').append(r.lastSeen()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"usage-" + usage.from() + "_" + usage.to() + ".csv\"")
                .body(csv.toString());
    }

    static List<UsageRow> aggregate(List<RequestLog.Entry> entries) {
        Map<String, List<RequestLog.Entry>> byUser = new TreeMap<>();
        for (RequestLog.Entry e : entries) {
            byUser.computeIfAbsent(e.user() == null ? "(no key)" : e.user(), k -> new ArrayList<>()).add(e);
        }
        List<UsageRow> rows = new ArrayList<>();
        byUser.forEach((user, list) -> {
            long[] durations = list.stream().mapToLong(RequestLog.Entry::durationMs).sorted().toArray();
            rows.add(new UsageRow(user, list.size(),
                    list.stream().filter(e -> e.status() >= 500 || e.status() == 0).count(),
                    list.stream().filter(e -> e.status() == 429).count(),
                    list.stream().filter(e -> e.status() == 401).count(),
                    percentile(durations, 0.50), percentile(durations, 0.95),
                    list.get(0).time(), list.get(list.size() - 1).time()));
        });
        rows.sort((a, b) -> Long.compare(b.calls(), a.calls()));
        return rows;
    }

    private static long percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)];
    }

    /** Guards against spreadsheet formula injection from user-controlled names. */
    private static String csvCell(String value) {
        String safe = value.matches("^[=+\\-@\\t\\r].*") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Dates must look like yyyy-MM-dd");
        }
    }

    private static Instant dayStart(String date, Instant fallback) {
        return date == null || date.isBlank() ? fallback
                : parseDate(date).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static Instant dayEnd(String date) {
        return date == null || date.isBlank() ? null
                : parseDate(date).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
