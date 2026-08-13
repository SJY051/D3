rootProject.name = "d3"

include(
    ":platform:discovery-server",
    ":platform:config-server",
    ":platform:api-gateway",
    ":services:identity-service",
    ":services:battle-service",
    ":services:judge-service",
    ":services:community-service",
)
