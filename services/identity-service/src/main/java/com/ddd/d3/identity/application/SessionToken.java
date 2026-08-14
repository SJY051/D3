package com.ddd.d3.identity.application;

import java.util.UUID;

// ponytail: refresh secret only; add the signed access token here in the JWT-issuance slice
public record SessionToken(UUID userId, String refreshToken) {}
