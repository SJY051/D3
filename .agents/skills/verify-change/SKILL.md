---
name: verify-change
description: Verify a D³ code or configuration change against linked requirements, tests, contracts, and repository boundaries. Use before reporting an implementation, fix, or refactor complete.
---

# Verify change

1. Resolve the linked issue and requirement IDs from the diff or ask for them.
2. Inspect every changed file and confirm the change stays inside the contract and service ownership boundary.
3. Map each acceptance criterion to evidence. Run the narrow deterministic checks first, then the relevant integration, contract, browser, or smoke checks.
4. Review generated files, migrations, API/event compatibility, secrets, and private-source exposure when applicable.
5. Report exact commands with pass, fail, skip, and not-run separately. Structure-only or skipped evidence never proves behavior.
6. Finish only when every acceptance criterion is evidenced or explicitly unresolved.

