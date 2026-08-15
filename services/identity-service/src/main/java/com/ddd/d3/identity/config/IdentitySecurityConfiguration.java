package com.ddd.d3.identity.config;

import com.ddd.d3.identity.adapter.http.IdentityErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class IdentitySecurityConfiguration {

    @Bean
    BrowserSessionCookiePolicy browserSessionCookiePolicy(
            Environment environment,
            @Value("${D3_REFRESH_COOKIE_SECURE:true}") boolean configuredSecure) {
        return BrowserSessionCookiePolicy.from(environment, configuredSecure);
    }

    @Bean
    AuthenticationProvider serviceTokenAuthenticationProvider(
            @Value("${D3_JWT_JUDGE_CLIENT_ID:battle-service}") String clientId,
            @Value("${D3_BATTLE_SERVICE_CLIENT_SECRET:}") String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("D3_BATTLE_SERVICE_CLIENT_SECRET is required");
        }
        var passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var user = User.withUsername(clientId)
                .password(passwordEncoder.encode(clientSecret))
                .authorities("SERVICE_TOKEN_ISSUE")
                .build();
        var provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(user));
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    @Order(1)
    SecurityFilterChain serviceTokenSecurityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            @Qualifier("serviceTokenAuthenticationProvider") AuthenticationProvider authenticationProvider)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "service authentication is required");
        AccessDeniedHandler forbidden = (request, response, exception) ->
                writeError(response, request, objectMapper, 403, "FORBIDDEN", "service caller is not authorized");
        return http.securityMatcher("/internal/v1/service-tokens")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/internal/v1/service-tokens")
                        .hasAuthority("SERVICE_TOKEN_ISSUE")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    @Bean
    JwtDecoder identityJwtDecoder(
            SigningKey signingKey,
            @Value("${D3_JWT_ISSUER:http://localhost:8081}") String issuer,
            @Value("${D3_JWT_USER_AUDIENCE:d3-user}") String audience,
            @Value("${D3_JWT_CLOCK_SKEW:30s}") Duration clockSkew) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.publicKey()).build();
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(clockSkew),
                new JwtIssuerValidator(issuer),
                audienceValidator));
        return decoder;
    }

    @Bean
    @Order(2)
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) ->
                writeError(response, request, objectMapper, 401, "UNAUTHORIZED", "authentication is required");
        AccessDeniedHandler forbidden = (request, response, exception) ->
                writeError(response, request, objectMapper, 403, "FORBIDDEN", "caller is not authorized");
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info", "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout")
                        .permitAll()
                        .requestMatchers("/v1/users/**").hasAuthority("SCOPE_identity.profile")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
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
        objectMapper.writeValue(response.getOutputStream(), new IdentityErrorResponse(code, message, correlationId));
    }
}
