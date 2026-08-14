package com.ddd.d3.community;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.initialization-fail-timeout=-1"
})
class CommunityServiceApplicationTest {

    @Autowired JwtDecoder jwtDecoder;

    @Test
    void d3Sec001StartsWithLocalJwtDefaults() {
        assertThat(jwtDecoder).isNotNull();
    }
}
