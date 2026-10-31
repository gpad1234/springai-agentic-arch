package com.example.springaigateway;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/** Global filters applied to every proxied request: logging, rate limiting and backend selection. */
final class ProxyFilters {

    private ProxyFilters() {
    }

    static String clientOf(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    static final String USER_ATTR = "gateway.user";
    static final String ERROR_ATTR = "gateway.errorCode";
    static final String CLIENT_KEY_HEADER = "X-API-Key";

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String errorCode) {
        exchange.getAttributes().put(ERROR_ATTR, errorCode);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
        }
        byte[] body = ("{\"error\":\"" + errorCode + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /** Maps connection failures to 502 and timeouts to 504; anything else is left to the default handling. */
    private static HttpStatus upstreamFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("Timeout")) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return HttpStatus.BAD_GATEWAY;
            }
        }
        return null;
    }

    @Component
    static class RequestLogFilter implements GlobalFilter, Ordered {

        private final RequestLog log;

        RequestLogFilter(RequestLog log) {
            this.log = log;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            long start = System.nanoTime();
            AtomicInteger errorStatus = new AtomicInteger();
            return chain.filter(exchange)
                    .onErrorResume(ex -> {
                        HttpStatus mapped = upstreamFailure(ex);
                        if (mapped == null || exchange.getResponse().isCommitted()) {
                            return Mono.error(ex);
                        }
                        return reject(exchange, mapped,
                                mapped == HttpStatus.GATEWAY_TIMEOUT ? "backend_timeout" : "backend_unreachable");
                    })
                    .doOnError(ex -> errorStatus.set(ex instanceof ResponseStatusException rse
                            ? rse.getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value()))
                    .doFinally(signal -> {
                        HttpStatusCode code = exchange.getResponse().getStatusCode();
                        int status = errorStatus.get() != 0 ? errorStatus.get() : code == null ? 0 : code.value();
                        log.add(new RequestLog.Entry(Instant.now().toString(), exchange.getRequest().getMethod().name(),
                                exchange.getRequest().getPath().value(), status, (System.nanoTime() - start) / 1_000_000,
                                clientOf(exchange), exchange.getAttribute(USER_ATTR), exchange.getAttribute(ERROR_ATTR)));
                    });
        }

        @Override
        public int getOrder() {
            return -3;
        }
    }

    /** Requires a valid {@code X-API-Key} when enabled, applies per-user limits and strips the header upstream. */
    @Component
    static class ClientKeyFilter implements GlobalFilter, Ordered {

        private final SettingsStore settings;
        private final ClientKeyStore clients;

        ClientKeyFilter(SettingsStore settings, ClientKeyStore clients) {
            this.settings = settings;
            this.clients = clients;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            if (settings.get().paused()) {
                return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "paused");
            }
            String presented = exchange.getRequest().getHeaders().getFirst(CLIENT_KEY_HEADER);
            ServerWebExchange forwarded = exchange;
            if (presented != null) {
                forwarded = exchange.mutate().request(r -> r.headers(h -> h.remove(CLIENT_KEY_HEADER))).build();
            }
            if (!settings.get().requireClientKey()) {
                return chain.filter(forwarded);
            }
            var client = clients.authenticate(presented);
            if (client.isEmpty()) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "unauthorized");
            }
            exchange.getAttributes().put(USER_ATTR, client.get().label());
            String limited = clients.consume(client.get(), Instant.now(), LocalDate.now());
            if (limited != null) {
                return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, limited);
            }
            return chain.filter(forwarded);
        }

        @Override
        public int getOrder() {
            return -1;
        }
    }

    /** Fixed one-minute window per client IP; a limit of 0 disables it. */
    @Component
    static class RateLimitFilter implements GlobalFilter, Ordered {

        private record Window(long minute, int count) {
        }

        private final SettingsStore settings;
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

        RateLimitFilter(SettingsStore settings) {
            this.settings = settings;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            int limit = settings.get().rateLimitPerMinute();
            if (limit <= 0 || allow(clientOf(exchange), limit, System.currentTimeMillis())) {
                return chain.filter(exchange);
            }
            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }

        boolean allow(String client, int limit, long nowMillis) {
            long minute = nowMillis / 60_000;
            if (windows.size() > 10_000) {
                windows.values().removeIf(w -> w.minute() != minute);
            }
            Window window = windows.merge(client, new Window(minute, 1),
                    (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
            return window.count() <= limit;
        }

        @Override
        public int getOrder() {
            return -2;
        }
    }

    /** Points every matched route at the currently configured backend URL. */
    @Component
    static class BackendUrlFilter implements GlobalFilter, Ordered {

        private final SettingsStore settings;

        BackendUrlFilter(SettingsStore settings) {
            this.settings = settings;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            URI current = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            if (current != null) {
                URI base = URI.create(settings.get().backendUrl());
                URI target = UriComponentsBuilder.fromUri(current)
                        .scheme(base.getScheme()).host(base.getHost()).port(base.getPort())
                        .build(true).toUri();
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, target);
            }
            return chain.filter(exchange);
        }

        @Override
        public int getOrder() {
            return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
        }
    }
}
