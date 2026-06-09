package vn.com.routex.hub.api.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RoutexApiGatewayApplicationTests {

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private GlobalCorsProperties globalCorsProperties;

    @Test
    void shouldRegisterRoutesForAllBackendServices() {
        List<RouteDefinition> routes = gatewayProperties.getRoutes();

        assertEquals(6, routes.size());
        assertTrue(hasRoute(routes, "user-service", "/api/v1/user-service/**"));
        assertTrue(hasRoute(routes, "user-service-management", "/api/v1/management/user-service/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/management/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/merchant-service/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/booking-service/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/payment-service/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/driver-service/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/v1/trip-passengers/**"));
        assertTrue(hasRoute(routes, "platform-core", "/api/recent-activities/**"));
        assertTrue(hasRoute(routes, "analytics-processor", "/api/v1/analytics-service/**"));
        assertTrue(hasRoute(routes, "notify-processor", "/api/v1/notify/**"));
        assertTrue(hasRoute(routes, "artifact-intelligence-service", "/api/v1/ai/**"));
    }

    @Test
    void shouldConfigureGatewayCorsForLocalFrontendOrigins() {
        var corsConfiguration = globalCorsProperties.getCorsConfigurations().get("/**");

        assertTrue(corsConfiguration.getAllowedOriginPatterns().contains("http://localhost:*"));
        assertTrue(corsConfiguration.getAllowedOriginPatterns().contains("http://127.0.0.1:*"));
        assertTrue(corsConfiguration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(Boolean.TRUE.equals(corsConfiguration.getAllowCredentials()));
    }

    @Test
    void shouldApplyDefaultFiltersForBrowserTraffic() {
        List<FilterDefinition> defaultFilters = gatewayProperties.getDefaultFilters();

        assertEquals(2, defaultFilters.size());
        assertTrue(defaultFilters.stream().anyMatch(filter -> filter.getName().equals("DedupeResponseHeader")));
        assertTrue(defaultFilters.stream().anyMatch(filter -> filter.getName().equals("PreserveHostHeader")));
    }

    private boolean hasRoute(List<RouteDefinition> routes, String routeId, String pathPattern) {
        return routes.stream()
                .filter(route -> route.getId().equals(routeId))
                .flatMap(route -> route.getPredicates().stream())
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .anyMatch(value -> value != null && value.contains(pathPattern));
    }
}
