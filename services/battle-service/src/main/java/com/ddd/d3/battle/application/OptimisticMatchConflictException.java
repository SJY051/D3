package com.ddd.d3.battle.application;

public final class OptimisticMatchConflictException extends RuntimeException {

    public OptimisticMatchConflictException() {
        super("Battle match changed before the command could be committed");
    }
}
