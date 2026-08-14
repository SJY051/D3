package com.ddd.d3.battle.application;

public final class CommandIdConflictException extends RuntimeException {

    public CommandIdConflictException() {
        super("command id was already used for a different battle command");
    }
}
