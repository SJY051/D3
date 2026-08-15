package com.ddd.d3.identity.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BrowserSessionCookiePolicyTest {

    @Test
    void d3Sec001AllowsInsecureCookiesOnlyWhenTheLocalProfileExplicitlyRequestsThem() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        MockEnvironment deployed = new MockEnvironment();
        deployed.setActiveProfiles("demo");

        assertFalse(BrowserSessionCookiePolicy.from(local, false).secure());
        assertTrue(BrowserSessionCookiePolicy.from(local, true).secure());
        assertTrue(BrowserSessionCookiePolicy.from(deployed, false).secure());
        assertTrue(BrowserSessionCookiePolicy.from(new MockEnvironment(), false).secure());
    }
}
