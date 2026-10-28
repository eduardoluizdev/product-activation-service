package com.example.activation.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    static final String SCOPE_READ = "SCOPE_activation:read";
    static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityHandlers securityHandlers(ObjectMapper objectMapper) {
        return new SecurityHandlers(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityHandlers handlers) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/**")
                        .hasAuthority(SCOPE_READ)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .build();
    }

    /**
     * Só existe quando {@code activation.security.local-jwt.hmac-secret} está definido (local/compose).
     * Sem ele, o Spring Boot cria o decoder a partir de {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
     */
    @Bean
    @ConditionalOnProperty("activation.security.local-jwt.hmac-secret")
    JwtDecoder localJwtDecoder(LocalJwtProperties properties) {
        var key = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audiência inválida", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audience));
        return decoder;
    }
}
