package com.ddd.d3.identity.application;

import java.util.List;
import java.util.Objects;

/**
 * Provisions rehearsal-only demo accounts at startup. The password is supplied through the environment
 * (never committed): with no password the seeder does nothing, so version control never carries a secret.
 * Re-running is safe — an already-provisioned account is skipped.
 */
public final class DemoUserSeeder {

    public record DemoUser(String email, String handle, String displayName) {}

    private final IdentityService identityService;
    private final List<DemoUser> demoUsers;
    private final String password;

    public DemoUserSeeder(IdentityService identityService, List<DemoUser> demoUsers, String password) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.demoUsers = List.copyOf(demoUsers);
        this.password = password;
    }

    /** @return the number of accounts newly created (0 when no password is configured or all already exist). */
    public int seed() {
        if (password == null || password.isBlank()) {
            return 0;
        }
        int created = 0;
        for (DemoUser user : demoUsers) {
            try {
                identityService.register(user.email(), user.handle(), user.displayName(), password);
                created++;
            } catch (DuplicateAccountException alreadyProvisioned) {
                // Idempotent restart: the demo account is already present.
            }
        }
        return created;
    }
}
