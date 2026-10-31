package com.example.springaigateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "gateway.data-dir=target/test-data/pilot", "gateway.default-rate-limit-per-minute=0",
                "gateway.require-client-key=true" })
class PilotIntegrationTest {

    private static HttpServer fakeBackend;

    @LocalServerPort
    int port;

    @Autowired
    SettingsStore settings;

    @BeforeAll
    static void start() throws IOException {
        Path dir = Path.of("target/test-data/pilot");
        if (Files.exists(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        fakeBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeBackend.createContext("/", exchange -> {
            String seen = exchange.getRequestHeaders().getFirst("X-API-Key");
            byte[] body = ("key-header:" + seen).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeBackend.start();
    }

    @AfterAll
    static void stop() {
        fakeBackend.stop(0);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void usersActivityAndUsage() {
        String backendUrl = "http://127.0.0.1:" + fakeBackend.getAddress().getPort();
        settings.update(backendUrl, 0);

        client().get().uri("/api/x").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("unauthorized");
        client().get().uri("/api/x").header("X-API-Key", "gw_wrong").exchange().expectStatus().isUnauthorized();

        String[] created = new String[2];
        client().post().uri("/admin/client-keys").header("X-Admin-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"label\":\"alice\",\"note\":\"pilot\",\"rateLimitPerMinute\":0,\"dailyCap\":2}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.client.id").value(v -> created[0] = (String) v)
                .jsonPath("$.key").value(v -> created[1] = (String) v);
        String id = created[0];
        String key = created[1];

        // The key is never returned again and the file holds only a hash.
        client().get().uri("/admin/client-keys").exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain(key).contains("alice"));

        client().get().uri("/api/x").header("X-API-Key", key).exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("key-header:null");
        client().get().uri("/api/x").header("X-API-Key", key).exchange().expectStatus().isOk();
        client().get().uri("/api/x").header("X-API-Key", key).exchange().expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.error").isEqualTo("daily_cap_reached");

        client().put().uri("/admin/client-keys/" + id).header("X-Admin-Request", "1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"revoked\":true}")
                .exchange().expectStatus().isOk();
        client().get().uri("/api/x").header("X-API-Key", key).exchange().expectStatus().isUnauthorized();

        client().get().uri("/admin/requests?user=alice").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].errorCode").isEqualTo("daily_cap_reached");
        client().get().uri("/admin/requests?status=client").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(4);

        client().get().uri("/admin/usage").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.rows[?(@.user=='alice')].calls").value(v -> assertThat(v.toString()).contains("3"))
                .jsonPath("$.rows[?(@.user=='alice')].limited").value(v -> assertThat(v.toString()).contains("1"));
        client().get().uri("/admin/usage.csv").exchange().expectStatus().isOk().expectBody(String.class)
                .value(csv -> assertThat(csv).startsWith("user,calls").contains("\"alice\""));
        client().get().uri("/admin/usage?from=nope").exchange().expectStatus().isBadRequest();

        client().delete().uri("/admin/client-keys/" + id).header("X-Admin-Request", "1").exchange()
                .expectStatus().isOk();
    }

    @Test
    void backendDownMapsTo502() {
        settings.update("http://127.0.0.1:1", 0, false, null, null);
        client().get().uri("/api/x").exchange().expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.error").isEqualTo("backend_unreachable");
        settings.update("http://127.0.0.1:1", 0, true, null, null);
    }

    @Test
    void pauseOverviewAndReport() {
        client().put().uri("/admin/settings").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paused\":true}").exchange().expectStatus().isOk();
        client().get().uri("/api/x").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("paused");
        client().get().uri("/admin/status").exchange().expectBody().jsonPath("$.paused").isEqualTo(true);
        client().put().uri("/admin/settings").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paused\":false}").exchange().expectStatus().isOk();

        client().get().uri("/admin/overview").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.days.length()").isEqualTo(7)
                .jsonPath("$.callsToday").value(v -> assertThat(((Number) v).intValue()).isGreaterThanOrEqualTo(1));

        client().post().uri("/admin/client-keys").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"label\":\"<b>x</b>\"}").exchange().expectStatus().isCreated();
        client().get().uri("/admin/report").exchange().expectStatus().isOk().expectBody(String.class)
                .value(html -> assertThat(html).contains("pilot report").doesNotContain("<b>x</b>"));
    }

    @Test
    void adminTestCallReachesBackendAndIsLogged() {
        settings.update("http://127.0.0.1:" + fakeBackend.getAddress().getPort(), 0, true, null, false);
        client().post().uri("/admin/test-call").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\":\"hi\"}").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo(200);
        client().post().uri("/admin/test-call").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"prompt\":\" \"}").exchange().expectStatus().isBadRequest();
        client().get().uri("/admin/requests?user=(admin test)").exchange().expectBody()
                .jsonPath("$[0].path").isEqualTo("/api/claude/chat");
    }
}
