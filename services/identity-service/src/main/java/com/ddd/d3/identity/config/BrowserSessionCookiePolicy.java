package com.ddd.d3.identity.config;

import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

public record BrowserSessionCookiePolicy(boolean secure) {

    static BrowserSessionCookiePolicy from(Environment environment, boolean configuredSecure) {
        Objects.requireNonNull(environment, "environment");
        boolean localHttpProfile = environment.acceptsProfiles(Profiles.of("local", "test"));
        return new BrowserSessionCookiePolicy(configuredSecure || !localHttpProfile);
    }
}
