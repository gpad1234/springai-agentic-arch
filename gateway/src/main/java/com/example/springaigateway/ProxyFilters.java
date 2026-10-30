package com.example.springaigateway;

import java.net.InetSocketAddress;
import java.net.URI;
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
                    .doOnError(ex -> errorStatus.set(ex instanceof ResponseStatusException rse
                            ? rse.getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value()))
                    .doFinally(signal -> {
                        HttpStatusCode code = exchange.getResponse().getStatusCode();
                        int status = errorStatus.get() != 0 ? errorStatus.get() : code == null ? 0 : code.value();
                        log.add(exchange.getRequest().getMethod().name(), exchange.getRequest().getPath().value(),
                                status, (System.nanoTime() - start) / 1_000_000, clientOf(exchange));
                    });
        }

        @Override
        public int getOrder() {
            return -2;
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
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
            return exchange.getResponse().setComplete();
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
            return -1;
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
