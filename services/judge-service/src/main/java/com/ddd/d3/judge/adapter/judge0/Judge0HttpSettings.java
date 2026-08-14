package com.ddd.d3.judge.adapter.judge0;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record Judge0HttpSettings(
        URI baseUri,
        URI allowedOrigin,
        String authenticationHeader,
        String authenticationToken,
        Duration requestTimeout,
        Duration pollInterval,
        Duration pollTimeout) {

    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    public Judge0HttpSettings {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(allowedOrigin, "allowedOrigin");
        Objects.requireNonNull(authenticationHeader, "authenticationHeader");
        Objects.requireNonNull(authenticationToken, "authenticationToken");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(pollTimeout, "pollTimeout");
        validateOrigin(baseUri, "base");
        validateOrigin(allowedOrigin, "allowed");
        if (!canonicalOrigin(baseUri).equals(canonicalOrigin(allowedOrigin))) {
            throw new IllegalArgumentException("Judge0 base URI does not match its allowed origin");
        }
        if (!HEADER_NAME.matcher(authenticationHeader).matches()) {
            throw new IllegalArgumentException("Judge0 authentication header is invalid");
        }
        if (authenticationToken.isBlank()
                || authenticationToken.chars().anyMatch(character -> character == '\r' || character == '\n')) {
            throw new IllegalArgumentException("Judge0 authentication token is invalid");
        }
        if (requestTimeout.isNegative()
                || requestTimeout.isZero()
                || pollInterval.isNegative()
                || pollInterval.isZero()
                || pollTimeout.isNegative()
                || pollTimeout.isZero()) {
            throw new IllegalArgumentException("Judge0 timeouts must be positive");
        }
    }

    private static void validateOrigin(URI origin, String label) {
        String scheme = origin.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("Judge0 " + label + " URI must be an HTTP(S) origin");
        }
    }

    private static String canonicalOrigin(URI origin) {
        String scheme = origin.getScheme().toLowerCase(java.util.Locale.ROOT);
        int port = origin.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + origin.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }
}
