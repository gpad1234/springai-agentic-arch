package com.example.springaigateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController
public class AdminController {

    public record Status(String backendUrl, boolean reachable, Integer httpStatus, Long latencyMs,
            int rateLimitPerMinute, String activeKeyLabel) {
    }

    public record KeysResponse(List<KeyStore.KeyView> keys, String activeKeyFile) {
    }

    public record SettingsRequest(String backendUrl, Integer rateLimitPerMinute) {
    }

    public record NewKeyRequest(String label, String key) {
    }

    private final SettingsStore settings;
    private final KeyStore keys;
    private final RequestLog requestLog;

    public AdminController(SettingsStore settings, KeyStore keys, RequestLog requestLog) {
        this.settings = settings;
        this.keys = keys;
        this.requestLog = requestLog;
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
        long start = System.nanoTime();
        return WebClient.create(current.backendUrl()).get().uri("/")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .timeout(Duration.ofSeconds(2))
                .map(code -> new Status(current.backendUrl(), true, code, (System.nanoTime() - start) / 1_000_000,
                        current.rateLimitPerMinute(), activeLabel))
                .onErrorReturn(new Status(current.backendUrl(), false, null, null, current.rateLimitPerMinute(),
                        activeLabel));
    }

    @GetMapping("/admin/settings")
    public SettingsStore.Settings getSettings() {
        return settings.get();
    }

    @PutMapping("/admin/settings")
    public SettingsStore.Settings putSettings(@RequestBody SettingsRequest request) {
        SettingsStore.Settings current = settings.get();
        return settings.update(request.backendUrl() == null ? current.backendUrl() : request.backendUrl(),
                request.rateLimitPerMinute() == null ? current.rateLimitPerMinute() : request.rateLimitPerMinute());
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

    @GetMapping("/admin/requests")
    public List<RequestLog.Entry> requests() {
        return requestLog.recent();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
