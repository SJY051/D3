package com.ddd.d3.identity.application;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("email or password is incorrect");
    }
}
