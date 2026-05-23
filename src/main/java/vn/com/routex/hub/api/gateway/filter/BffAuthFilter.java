package vn.com.routex.hub.api.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

@Component
public class BffAuthFilter implements WebFilter, Ordered {

    @Value("${app.services.user-service-uri:http://localhost:8081}")
    private String userServiceUri;

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    
    private static final String LOGIN_PATH = "/api/v1/user-service/authentication/login";
    private static final String LOGOUT_PATH = "/api/v1/user-service/authentication/logout";
    private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    private static final String CLIENT_TYPE_WEB = "Web";

    public BffAuthFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    @Override
    public int getOrder() {
        // Run early in the chain, before routing filters
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String clientType = request.getHeaders().getFirst(CLIENT_TYPE_HEADER);
        boolean isWeb = CLIENT_TYPE_WEB.equalsIgnoreCase(clientType);

        if (!isWeb) {
            // Mobile or other client: check if request has session cookie anyway to relay token
            return exchange.getSession()
                .flatMap(session -> {
                    String accessToken = session.getAttribute("accessToken");
                    if (accessToken != null) {
                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .build();
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    }
                    return chain.filter(exchange);
                });
        }

        // Handle Web Login
        if (HttpMethod.POST.equals(request.getMethod()) && LOGIN_PATH.equals(path)) {
            return handleWebLogin(exchange, chain);
        }

        // Handle Web Logout
        if (HttpMethod.POST.equals(request.getMethod()) && LOGOUT_PATH.equals(path)) {
            return handleWebLogout(exchange, chain);
        }

        // Handle Web general API request (Token Relay)
        return exchange.getSession()
            .flatMap(session -> {
                String accessToken = session.getAttribute("accessToken");
                if (accessToken == null) {
                    // Let the request go downstream without the Authorization header.
                    // Downstream microservices will reject with a 401 Unauthorized response,
                    // which correctly passes through the Gateway CORS filters.
                    return chain.filter(exchange);
                }

                if (isJwtExpired(accessToken)) {
                    String refreshToken = session.getAttribute("refreshToken");
                    if (refreshToken != null) {
                        return refreshAccessToken(exchange, refreshToken)
                            .flatMap(newAccessToken -> {
                                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken)
                                        .build();
                                return chain.filter(exchange.mutate().request(mutatedRequest).build());
                            })
                            .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
                    }
                }

                // Relay token
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            });
    }

    private boolean isPublicPath(String path) {
        return path.contains("/internal/") ||
               path.contains("/provinces/search") ||
               path.contains("/campaigns/validate") ||
               path.contains("/swagger") ||
               path.contains("/v3/api-docs") ||
               path.contains("/actuator") ||
               path.contains("/error");
    }

    private Mono<Void> handleWebLogin(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                return DataBufferUtils.join(fluxBody)
                    .flatMap(dataBuffer -> {
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        DataBufferUtils.release(dataBuffer);

                        String responseBody = new String(content, StandardCharsets.UTF_8);
                        try {
                            JsonNode rootNode = objectMapper.readTree(responseBody);
                            JsonNode dataNode = rootNode.path("data");
                            
                            if (dataNode.isObject()) {
                                String accessToken = dataNode.path("accessToken").asText(null);
                                String refreshToken = dataNode.path("refreshToken").asText(null);

                                if (accessToken != null && !accessToken.isBlank() && refreshToken != null && !refreshToken.isBlank()) {
                                    // Parse JWT payload (part index 1) to extract merchantId
                                    String merchantId = null;
                                    try {
                                        String[] parts = accessToken.split("\\.");
                                        if (parts.length > 1) {
                                            byte[] decodedBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
                                            String payload = new String(decodedBytes, StandardCharsets.UTF_8);
                                            JsonNode jwtClaims = objectMapper.readTree(payload);
                                            JsonNode merchantIdNode = jwtClaims.path("merchantId");
                                            if (!merchantIdNode.isMissingNode() && !merchantIdNode.isNull()) {
                                                merchantId = merchantIdNode.asText(null);
                                            }
                                        }
                                    } catch (Exception e) {
                                        // Ignore parsing errors
                                    }

                                    final String finalMerchantId = merchantId;

                                    // Save tokens in session
                                    return exchange.getSession().flatMap(session -> {
                                        session.getAttributes().put("accessToken", accessToken);
                                        session.getAttributes().put("refreshToken", refreshToken);
                                        
                                        // Strip tokens from response body
                                        ObjectNode dataObj = (ObjectNode) dataNode;
                                        dataObj.putNull("accessToken");
                                        dataObj.putNull("refreshToken");

                                        // Inject merchantId so that the frontend can read it from the body
                                        if (finalMerchantId != null) {
                                            dataObj.put("merchantId", finalMerchantId);
                                        }

                                        byte[] modifiedContent;
                                        try {
                                            modifiedContent = objectMapper.writeValueAsBytes(rootNode);
                                        } catch (JsonProcessingException e) {
                                            modifiedContent = content;
                                        }

                                        getHeaders().setContentLength(modifiedContent.length);
                                        return getDelegate().writeWith(Mono.just(bufferFactory.wrap(modifiedContent)));
                                    });
                                }
                            }
                        } catch (Exception e) {
                            // Fallback to original response in case of parsing errors
                        }
                        getHeaders().setContentLength(content.length);
                        return getDelegate().writeWith(Mono.just(bufferFactory.wrap(content)));
                    });
            }
        };

        return chain.filter(exchange.mutate().response(responseDecorator).build());
    }

    private Mono<Void> handleWebLogout(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getSession().flatMap(session -> {
            String refreshToken = session.getAttribute("refreshToken");
            if (refreshToken == null) {
                return session.invalidate().then(Mono.defer(() -> {
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().setComplete();
                }));
            }

            // Intercept request body to inject refreshToken
            return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] content = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(content);
                    DataBufferUtils.release(dataBuffer);

                    String requestBody = new String(content, StandardCharsets.UTF_8);
                    byte[] modifiedContent = content;
                    try {
                        JsonNode rootNode = objectMapper.readTree(requestBody);
                        JsonNode dataNode = rootNode.path("data");
                        if (dataNode.isObject()) {
                            ObjectNode dataObj = (ObjectNode) dataNode;
                            dataObj.put("refreshToken", refreshToken);
                            modifiedContent = objectMapper.writeValueAsBytes(rootNode);
                        }
                    } catch (Exception e) {
                        // ignore and use original content
                    }

                    byte[] finalModifiedContent = modifiedContent;
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders httpHeaders = new HttpHeaders();
                            httpHeaders.putAll(super.getHeaders());
                            httpHeaders.setContentLength(finalModifiedContent.length);
                            httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                            return httpHeaders;
                        }

                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(finalModifiedContent));
                        }
                    };

                    return chain.filter(exchange.mutate().request(mutatedRequest).build())
                        .then(Mono.defer(() -> session.invalidate()));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    return session.invalidate().then(chain.filter(exchange));
                }));
        });
    }

    private boolean isJwtExpired(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length > 1) {
                byte[] decodedBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
                String payload = new String(decodedBytes, StandardCharsets.UTF_8);
                JsonNode jwtClaims = objectMapper.readTree(payload);
                long exp = jwtClaims.path("exp").asLong(0);
                long now = Instant.now().getEpochSecond();
                return exp < (now + 30); // expired or expires in less than 30 seconds
            }
        } catch (Exception e) {
            return true; // Treat as expired on parsing error
        }
        return true;
    }

    private Mono<String> refreshAccessToken(ServerWebExchange exchange, String refreshToken) {
        String requestId = java.util.UUID.randomUUID().toString();
        String requestDateTime = java.time.OffsetDateTime.now().toString();

        Map<String, Object> requestBody = Map.of(
            "requestId", requestId,
            "requestDateTime", requestDateTime,
            "channel", "ONL",
            "data", Map.of("refreshToken", refreshToken)
        );

        return webClient.post()
            .uri(userServiceUri + "/api/v1/user-service/refresh-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .flatMap(responseNode -> {
                JsonNode dataNode = responseNode.path("data");
                if (dataNode.isObject()) {
                    String newAccessToken = dataNode.path("accessToken").asText(null);
                    String newRefreshToken = dataNode.path("refreshToken").asText(null);
                    if (newAccessToken != null && newRefreshToken != null) {
                        return exchange.getSession().flatMap(session -> {
                            session.getAttributes().put("accessToken", newAccessToken);
                            session.getAttributes().put("refreshToken", newRefreshToken);
                            return session.save().then(Mono.just(newAccessToken));
                        });
                    }
                }
                return Mono.error(new RuntimeException("Invalid refresh token response"));
            })
            .onErrorResume(err -> {
                // If refresh fails, invalidate the session
                return exchange.getSession().flatMap(session -> session.invalidate().then(Mono.empty()));
            });
    }
}
