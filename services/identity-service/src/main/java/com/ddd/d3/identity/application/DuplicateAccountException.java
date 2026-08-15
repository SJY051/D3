package com.ddd.d3.identity.application;

public class DuplicateAccountException extends RuntimeException {

    public DuplicateAccountException() {
        super("an account with this email or handle already exists");
    }
}
