package com.ddd.d3.judge.config;

import com.ddd.d3.judge.adapter.http.JudgeErrorResponse;
import com.ddd.d3.judge.adapter.http.JudgeRequestSizeFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class JudgeSecurityConfiguration {

    @Bean
    JwtDecoder judgeJwtDecoder(
            @Value("${d3.security.jwk-set-uri}") String jwkSetUri,
            @Value("${d3.security.issuer}") String issuer,
            @Value("${d3.security.audience:judge-service}") String audience,
            @Value("${d3.security.allowed-client-id:battle-service}") String allowedClientId,
            @Value("${d3.security.allowed-token-use:service}") String allowedTokenUse,
            @Value("${d3.security.clock-skew:30s}") Duration clockSkew) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(judgeTokenValidator(issuer, audience, allowedClientId, allowedTokenUse, clockSkew));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> judgeTokenValidator(
            String issuer,
            String audience,
            String allowedClientId,
            String allowedTokenUse,
            Duration clockSkew) {
        OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator(clockSkew);
        OAuth2TokenValidator<Jwt> issuerValidator = new JwtIssuerValidator(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience));
        OAuth2TokenValidator<Jwt> clientValidator = new JwtClaimValidator<String>(
                "client_id", allowedClientId::equals);
        OAuth2TokenValidator<Jwt> tokenUseValidator = new JwtClaimValidator<String>(
                "token_use", allowedTokenUse::equals);
        return new DelegatingOAuth2TokenValidator<>(
                timestampValidator, issuerValidator, audienceValidator, clientValidator, tokenUseValidator);
    }

    @Bean
    SecurityFilterChain judgeSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            @Value("${d3.security.allowed-client-id:battle-service}") String allowedClientId,
            @Value("${d3.security.allowed-token-use:service}") String allowedTokenUse) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "service authentication is required");
        AccessDeniedHandler forbidden = (request, response, exception) ->
                writeError(response, request, objectMapper, 403, "FORBIDDEN", "service caller is not authorized");
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/internal/v1/judge/submissions")
                        .access(serviceAuthority("SCOPE_judge.submit", allowedClientId, allowedTokenUse))
                        .requestMatchers(HttpMethod.GET, "/internal/v1/judge/submissions/*/evidence")
                        .access(serviceAuthority("SCOPE_judge.read", allowedClientId, allowedTokenUse))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .addFilterAfter(new JudgeRequestSizeFilter(objectMapper), BearerTokenAuthenticationFilter.class)
                .build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> serviceAuthority(
            String requiredAuthority, String allowedClientId, String allowedTokenUse) {
        return (authentication, context) -> {
            var current = authentication.get();
            boolean granted = current.getPrincipal() instanceof Jwt jwt
                    && allowedClientId.equals(jwt.getClaimAsString("client_id"))
                    && allowedTokenUse.equals(jwt.getClaimAsString("token_use"))
                    && current.getAuthorities().stream()
                            .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
            return new AuthorizationDecision(granted);
        };
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
        objectMapper.writeValue(response.getOutputStream(), new JudgeErrorResponse(code, message, correlationId));
    }
}
