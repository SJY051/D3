package com.ddd.d3.identity.application;

public class RefreshTokenRejectedException extends RuntimeException {

    public RefreshTokenRejectedException() {
        super("refresh token is unknown, expired, or already used");
    }
}
