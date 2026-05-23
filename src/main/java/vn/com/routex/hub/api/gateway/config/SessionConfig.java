package vn.com.routex.hub.api.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

@Configuration
@EnableRedisWebSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes
public class SessionConfig {

    @Value("${app.security.cookie-domain:localhost}")
    private String cookieDomain;

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return RedisSerializer.json();
    }

    @Bean
    public WebSessionIdResolver webSessionIdResolver() {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.setCookieName("SESSION");
        resolver.addCookieInitializer(builder -> {
            builder.path("/")
                   .httpOnly(true);
            
            if (!"localhost".equalsIgnoreCase(cookieDomain) && !"127.0.0.1".equals(cookieDomain) && !cookieDomain.isBlank()) {
                builder.domain(cookieDomain)
                       .secure(true) // Require HTTPS for production subdomains
                       .sameSite("Lax");
            } else {
                builder.secure(false) // Allow HTTP for localhost development
                       .sameSite("Lax");
            }
        });
        return resolver;
    }
}
