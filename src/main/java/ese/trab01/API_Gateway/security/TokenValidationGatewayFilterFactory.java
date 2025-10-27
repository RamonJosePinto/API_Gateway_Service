package ese.trab01.API_Gateway.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class TokenValidationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TokenValidationGatewayFilterFactory.Config> {

    public static class Config {}

    private final WebClient webClient;

    @Value("${app.auth.validate-url}")
    private String validateUrl;

    @Value("${app.auth.timeout-ms:2500}")
    private long timeoutMs;

    // 👇 IMPORTANTE: registra explicitamente o tipo do config
    public TokenValidationGatewayFilterFactory(WebClient webClient) {
        super(Config.class);
        this.webClient = webClient;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
                return unauthorized(exchange, "Missing or invalid Authorization header");
            }
            String token = authHeader.substring(7).trim();

            return webClient.post()
                    .uri(validateUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("token", token))
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(),
                            resp -> resp.bodyToMono(String.class)
                                    .defaultIfEmpty("Unauthorized")
                                    .map(msg -> new RuntimeException("401:" + msg))
                    )
                    .onStatus(status -> status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class)
                                    .defaultIfEmpty("Auth service error")
                                    .map(msg -> new RuntimeException("502:" + msg))
                    )
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .flatMap(body -> {
                        var sanitizedReq = exchange.getRequest().mutate()
                                .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Name"); h.remove("X-User-Roles"); })
                                .header("X-User-Id", String.valueOf(body.getOrDefault("userId", "")))
                                .header("X-User-Name", String.valueOf(body.getOrDefault("username", "")))
                                .header("X-User-Roles", String.join(",",
                                        ((List<?>) body.getOrDefault("roles", List.of())).stream().map(Object::toString).toList()))
                                .build();
                        return chain.filter(exchange.mutate().request(sanitizedReq).build());
                    })
                    .onErrorResume(ex -> {
                        String msg = ex.getMessage() == null ? "" : ex.getMessage();
                        if (ex instanceof java.util.concurrent.TimeoutException) return gatewayError(exchange, "Auth service timeout");
                        if (msg.startsWith("401:")) return unauthorized(exchange, "Unauthorized");
                        if (msg.startsWith("502:")) return gatewayError(exchange, "Auth service error");
                        return unauthorized(exchange, "Token validation failed");
                    });
        };
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var bytes = ("{\"error\":\"" + message + "\"}").getBytes();
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    private Mono<Void> gatewayError(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var bytes = ("{\"error\":\"" + message + "\"}").getBytes();
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Component
    public static class WebClientConfig {
        @Bean
        WebClient webClient() {
            var httpClient = HttpClient.create();
            return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        }
    }
}
