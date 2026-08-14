package com.ddd.d3.battle;

import com.ddd.d3.battle.application.PublicRatingReader;
import com.ddd.d3.battle.application.BattleCommandReceiptStore;
import com.ddd.d3.battle.application.BattleMatchCommandService;
import com.ddd.d3.battle.application.BattleMatchRepository;
import com.ddd.d3.battle.application.BattleMatchViewService;
import com.ddd.d3.battle.application.RankedMatchStore;
import com.ddd.d3.battle.application.RankedMatchmakingCoordinator;
import com.ddd.d3.battle.application.RankedQueueStore;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class BattleServiceConfiguration {

    @Bean
    Clock battleClock() {
        return Clock.systemUTC();
    }

    @Bean
    RankedMatchmaker rankedMatchmaker(
            @Value("${d3.battle.ranked-matchmaking.initial-window:100}") int initialWindow,
            @Value("${d3.battle.ranked-matchmaking.widening-step:50}") int wideningStep,
            @Value("${d3.battle.ranked-matchmaking.widening-interval:10s}") Duration wideningInterval,
            @Value("${d3.battle.ranked-matchmaking.maximum-window:300}") int maximumWindow) {
        return new RankedMatchmaker(new RankedMatchmaker.Policy(
                initialWindow, wideningStep, wideningInterval, maximumWindow));
    }

    @Bean
    RankedMatchmakingCoordinator rankedMatchmakingCoordinator(
            RankedMatchmaker matchmaker,
            RankedQueueStore queue,
            RankedMatchStore matches,
            PublicRatingReader ratings,
            Clock clock,
            @Value("${d3.battle.ranked-matchmaking.entry-ttl:2m}") Duration entryTtl,
            @Value("${d3.battle.ranked-matchmaking.lease-ttl:5s}") Duration leaseTtl) {
        return new RankedMatchmakingCoordinator(
                matchmaker, queue, matches, ratings, clock, entryTtl, leaseTtl);
    }

    @Bean
    BattleMatchCommandService battleMatchCommandService(
            BattleMatchRepository matches,
            BattleCommandReceiptStore receipts,
            Clock clock,
            @Value("${d3.battle.match-duration:10m}") Duration matchDuration,
            PlatformTransactionManager transactionManager) {
        return new BattleMatchCommandService(
                matches, receipts, clock, matchDuration, new TransactionTemplate(transactionManager));
    }

    @Bean
    BattleMatchViewService battleMatchViewService(BattleMatchRepository matches, Clock clock) {
        return new BattleMatchViewService(matches, clock);
    }
}
