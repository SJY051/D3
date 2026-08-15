package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleConnectionGenerationSource;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBattleConnectionGenerationSource implements BattleConnectionGenerationSource {

    private final JdbcClient jdbc;

    public JdbcBattleConnectionGenerationSource(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public long nextGeneration() {
        return jdbc.sql("select nextval('battle_connection_generation_seq')")
                .query(Long.class)
                .single();
    }
}
