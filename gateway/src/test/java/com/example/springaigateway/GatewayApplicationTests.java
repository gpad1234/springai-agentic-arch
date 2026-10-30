package com.example.springaigateway;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "gateway.data-dir=target/test-data/context")
class GatewayApplicationTests {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void configuresApiRoute() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        Route apiRoute = routes.stream()
                .filter(route -> route.getId().equals("spring-ai-agents-api"))
                .findFirst()
                .orElseThrow();

        assertThat(apiRoute.getUri()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(Mono.from(apiRoute.getPredicate().apply(exchange("/api/claude/chat"))).block()).isTrue();
        assertThat(Mono.from(apiRoute.getPredicate().apply(exchange("/"))).block()).isFalse();
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
