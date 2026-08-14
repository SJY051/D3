package com.ddd.d3.battle.application;

import java.util.UUID;

@FunctionalInterface
public interface PublicRatingReader {

    int publicRating(UUID playerId);
}
