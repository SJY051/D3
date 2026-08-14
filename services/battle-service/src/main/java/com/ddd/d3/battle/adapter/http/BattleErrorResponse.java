package com.ddd.d3.battle.adapter.http;

public record BattleErrorResponse(String code, String message, String correlationId) {}
