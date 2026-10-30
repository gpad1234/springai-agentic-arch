package com.example.springaigateway;

import java.net.InetSocketAddress;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Restricts {@code /admin/**} to local use: loopback client, local Host header (blocks DNS rebinding) and,
 * for state-changing calls, a custom header (blocks cross-site form posts).
 */
@Component
public class AdminAccessFilter implements WebFilter {

    public static final String ADMIN_HEADER = "X-Admin-Request";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!path.equals("/admin") && !path.startsWith("/admin/")) {
            return chain.filter(exchange);
        }
        InetSocketAddress remote = request.getRemoteAddress();
        boolean loopback = remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
        String host = request.getHeaders().getHost() == null ? null : request.getHeaders().getHost().getHostString();
        boolean localHost = host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        boolean mutation = !HttpMethod.GET.equals(request.getMethod()) && !HttpMethod.HEAD.equals(request.getMethod());
        boolean headerOk = !mutation || request.getHeaders().containsKey(ADMIN_HEADER);
        if (loopback && localHost && headerOk) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}
