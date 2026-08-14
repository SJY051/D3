package com.ddd.d3.community;

import com.ddd.d3.community.adapter.http.CommunityErrorResponse;
import com.ddd.d3.community.config.CommunityRequestSizeFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class CommunitySecurityConfiguration {

    @Bean
    JwtDecoder communityJwtDecoder(
            @Value("${d3.security.jwk-set-uri}") String jwkSetUri,
            @Value("${d3.security.issuer}") String issuer,
            @Value("${d3.security.audience:community-service}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    @Bean
    SecurityFilterChain communitySecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v1/community/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, request, objectMapper, 403, "FORBIDDEN", "request is forbidden")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, request, objectMapper, 403, "FORBIDDEN", "request is forbidden")))
                .addFilterAfter(new CommunityRequestSizeFilter(objectMapper), BearerTokenAuthenticationFilter.class)
                .build();
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
        objectMapper.writeValue(response.getOutputStream(), new CommunityErrorResponse(code, message, correlationId));
    }
}
