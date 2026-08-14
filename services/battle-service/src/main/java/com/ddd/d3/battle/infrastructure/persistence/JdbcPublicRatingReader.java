package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.PublicRatingReader;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcPublicRatingReader implements PublicRatingReader {

    private final JdbcClient jdbc;
    private final int initialRating;

    public JdbcPublicRatingReader(
            DataSource dataSource,
            @Value("${d3.battle.ranked-matchmaking.initial-rating:1500}") int initialRating) {
        this.jdbc = JdbcClient.create(dataSource);
        if (initialRating < 0) {
            throw new IllegalArgumentException("initialRating must not be negative");
        }
        this.initialRating = initialRating;
    }

    @Override
    public int publicRating(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId must not be null");
        return jdbc.sql("select public_rating from rating where user_id = :playerId")
                .param("playerId", playerId)
                .query(Integer.class)
                .optional()
                .orElse(initialRating);
    }
}
