package com.ddd.d3.battle.application;

public final class NoActiveRankedProblemException extends RuntimeException {

    public NoActiveRankedProblemException() {
        super("No active problem is available for ranked play");
    }
}
