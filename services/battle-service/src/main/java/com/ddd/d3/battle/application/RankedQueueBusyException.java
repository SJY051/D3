package com.ddd.d3.battle.application;

public final class RankedQueueBusyException extends RuntimeException {

    public RankedQueueBusyException() {
        super("Ranked queue coordination is busy");
    }
}
