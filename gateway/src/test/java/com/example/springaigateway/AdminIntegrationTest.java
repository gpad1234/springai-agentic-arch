package com.example.springaigateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
        properties = { "gateway.data-dir=target/test-data/admin", "gateway.default-rate-limit-per-minute=0" })
class AdminIntegrationTest {

    private static HttpServer fakeBackend;

    @LocalServerPort
    int port;

    @Autowired
    SettingsStore settings;

    @BeforeAll
    static void startBackend() throws IOException {
        fakeBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeBackend.createContext("/", exchange -> {
            byte[] body = ("backend:" + exchange.getRequestURI()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fakeBackend.start();
    }

    @AfterAll
    static void stopBackend() {
        fakeBackend.stop(0);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void proxiesToSwitchedBackendLogsAndRateLimits() {
        String backendUrl = "http://127.0.0.1:" + fakeBackend.getAddress().getPort();

        client().put().uri("/admin/settings").header("X-Admin-Request", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"backendUrl\":\"" + backendUrl + "\",\"rateLimitPerMinute\":2}")
                .exchange().expectStatus().isOk();

        client().get().uri("/admin/status").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.reachable").isEqualTo(true).jsonPath("$.backendUrl").isEqualTo(backendUrl);

        client().get().uri("/api/claude/chat?x=1").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("backend:/api/claude/chat?x=1");
        client().get().uri("/api/second").exchange().expectStatus().isOk();
        client().get().uri("/api/third").exchange().expectStatus().isEqualTo(429);

        client().get().uri("/admin/requests").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$[0].status").isEqualTo(429)
                .jsonPath("$[0].path").isEqualTo("/api/third")
                .jsonPath("$[2].path").isEqualTo("/api/claude/chat");

        settings.update(backendUrl, 0);
    }

    @Test
    void adminRequiresLocalHostAndHeaderForChanges() {
        client().put().uri("/admin/settings").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rateLimitPerMinute\":5}").exchange().expectStatus().isForbidden();

        client().get().uri("/admin/settings").header("Host", "evil.example.com").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void keyEndpointsNeverReturnFullKeys() {
        client().post().uri("/admin/keys").header("X-Admin-Request", "1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"label\":\"test\",\"key\":\"sk-ant-" + "i".repeat(45) + "9999\"}")
                .exchange().expectStatus().isCreated();

        String body = client().get().uri("/admin/keys").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("••••9999").doesNotContain("sk-ant-api03");
    }

    @Test
    void servesAdminPage() {
        client().get().uri("/admin/index.html").exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
        client().get().uri("/admin").exchange().expectStatus().isFound();
        client().get().uri("/admin/").exchange().expectStatus().isFound()
                .expectHeader().valueEquals("Location", "/admin/index.html");
    }
}
