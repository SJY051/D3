package com.ddd.d3.identity;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("D3-ID-001 P0 local auth is implemented and verified by IdentityServiceTest, "
        + "JdbcIdentityRepositoryTest, AccessTokenIssuerTest, and IdentityControllerTest. "
        + "This placeholder tracks the still-unimplemented P1 GitHub OAuth linking.")
class IdentityRequirementScaffoldTest {

    // D3-ID-001 P1: explicit GitHub OAuth linking without email-based silent merge (not yet implemented).
    @Test
    void d3Id001LinksGithubOauthWithoutSilentEmailMerge() {
        // Enable when the P1 OAuth linking feature boundary is activated.
    }
}
