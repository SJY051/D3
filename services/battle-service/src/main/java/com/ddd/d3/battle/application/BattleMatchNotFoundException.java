package com.ddd.d3.battle.application;

public final class BattleMatchNotFoundException extends RuntimeException {

    public BattleMatchNotFoundException() {
        super("battle match was not found");
    }
}
