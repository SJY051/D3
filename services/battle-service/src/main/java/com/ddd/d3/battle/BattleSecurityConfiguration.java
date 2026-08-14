package com.ddd.d3.battle;

import com.ddd.d3.battle.adapter.http.BattleErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class BattleSecurityConfiguration {

    @Bean
    JwtDecoder battleJwtDecoder(
            @Value("${d3.security.jwk-set-uri}") String jwkSetUri,
            @Value("${d3.security.issuer}") String issuer,
            @Value("${d3.security.audience:d3-user}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience));
        OAuth2TokenValidator<Jwt> subjectValidator = new JwtClaimValidator<String>(
                "sub", BattleSecurityConfiguration::isUuid);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, subjectValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain battleSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "authentication is required");
        AccessDeniedHandler forbidden = (request, response, exception) ->
                writeError(response, request, objectMapper, 403, "FORBIDDEN", "caller is not authorized");
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/battle/ranked/queue")
                        .hasAuthority("SCOPE_battle.play")
                        .anyRequest()
                        .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message) throws IOException {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = "unavailable";
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(), new BattleErrorResponse(code, message, correlationId));
    }
}
