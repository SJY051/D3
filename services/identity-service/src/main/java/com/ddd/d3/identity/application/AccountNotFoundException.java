package com.ddd.d3.identity.application;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("the account for this session no longer exists");
    }
}
