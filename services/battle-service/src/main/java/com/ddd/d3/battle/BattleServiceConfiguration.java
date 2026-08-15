package com.ddd.d3.battle;

import com.ddd.d3.battle.application.BattleCommandReceiptStore;
import com.ddd.d3.battle.application.BattleAttackService;
import com.ddd.d3.battle.application.BattleConnectionGenerationSource;
import com.ddd.d3.battle.application.BattleJudgeCommandService;
import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import com.ddd.d3.battle.application.BattleJudgedSubmissionService;
import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.BattleMatchCommandService;
import com.ddd.d3.battle.application.BattleMatchRepository;
import com.ddd.d3.battle.application.BattleMatchViewService;
import com.ddd.d3.battle.application.GarbageAttackEventStore;
import com.ddd.d3.battle.application.BattleDeadlineClaimStore;
import com.ddd.d3.battle.application.BattleDeadlineScheduler;
import com.ddd.d3.battle.application.BattleDeadlineService;
import com.ddd.d3.battle.application.BattleSnapshotPublisher;
import com.ddd.d3.battle.application.BattleEventPublisher;
import com.ddd.d3.battle.application.BattleOutboxDispatcher;
import com.ddd.d3.battle.application.BattleOutboxStore;
import com.ddd.d3.battle.application.BattleResultScheduler;
import com.ddd.d3.battle.application.BattleResultService;
import com.ddd.d3.battle.application.BattleResultStore;
import com.ddd.d3.battle.application.JudgeServiceTokenProvider;
import com.ddd.d3.battle.application.PublicRatingReader;
import com.ddd.d3.battle.application.RankedMatchStore;
import com.ddd.d3.battle.application.RankedMatchmakingCoordinator;
import com.ddd.d3.battle.application.RankedQueueStore;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import com.ddd.d3.battle.domain.attack.GarbageAttackExchange;
import com.ddd.d3.battle.domain.outcome.MatchScoreCalculator;
import com.ddd.d3.battle.domain.outcome.RatingProgressionCalculator;
import com.ddd.d3.battle.infrastructure.messaging.KafkaBattleEventPublisher;
import com.ddd.d3.battle.infrastructure.messaging.ScheduledBattleOutboxPublisher;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
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
            PlatformTransactionManager transactionManager,
            BattleSnapshotPublisher snapshots) {
        return new BattleMatchCommandService(
                matches,
                receipts,
                clock,
                matchDuration,
                new TransactionTemplate(transactionManager),
                snapshots);
    }

    @Bean
    BattleConnectionService battleConnectionService(
            BattleMatchRepository matches,
            BattleConnectionGenerationSource generations,
            Clock clock,
            PlatformTransactionManager transactionManager,
            BattleSnapshotPublisher snapshots,
            @Value("${d3.battle.connection.maximum-attempts:3}") int maximumAttempts) {
        return new BattleConnectionService(
                matches,
                generations,
                clock,
                new TransactionTemplate(transactionManager),
                snapshots,
                maximumAttempts);
    }

    @Bean
    BattleMatchViewService battleMatchViewService(BattleMatchRepository matches, Clock clock) {
        return new BattleMatchViewService(matches, clock);
    }

    @Bean
    BattleAttackService battleAttackService(
            BattleMatchRepository matches,
            GarbageAttackEventStore events,
            BattleCommandReceiptStore receipts,
            Clock clock,
            PlatformTransactionManager transactionManager,
            BattleSnapshotPublisher snapshots) {
        return new BattleAttackService(
                matches,
                events,
                receipts,
                clock,
                GarbageAttackExchange.Policy.initial(),
                () -> ThreadLocalRandom.current().nextLong(),
                new TransactionTemplate(transactionManager),
                snapshots);
    }

    @Bean
    BattleJudgeCommandService battleJudgeCommandService(
            BattleJudgeReferenceStore references,
            BattleCommandReceiptStore receipts,
            BattleJudgeGateway judge,
            JudgeServiceTokenProvider tokens,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new BattleJudgeCommandService(
                references,
                receipts,
                judge,
                tokens,
                clock,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    BattleJudgedSubmissionService battleJudgedSubmissionService(
            BattleJudgeReferenceStore references,
            BattleJudgeGateway judge,
            JudgeServiceTokenProvider tokens,
            BattleMatchRepository matches,
            BattleSnapshotPublisher snapshots,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        return new BattleJudgedSubmissionService(
                references,
                judge,
                tokens,
                matches,
                snapshots,
                clock,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    BattleResultService battleResultService(
            BattleResultStore results,
            BattleMatchRepository matches,
            BattleSnapshotPublisher snapshots,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${d3.battle.scoring.version:score-v1}") String scoreVersion,
            @Value("${d3.battle.scoring.speed-weight:0.50}") BigDecimal speedWeight,
            @Value("${d3.battle.scoring.dynamic-efficiency-weight:0.35}") BigDecimal efficiencyWeight,
            @Value("${d3.battle.scoring.submission-discipline-weight:0.15}") BigDecimal disciplineWeight) {
        MatchScoreCalculator scoreCalculator = new MatchScoreCalculator(
                scoreVersion,
                new MatchScoreCalculator.ScoringWeights(
                        speedWeight, efficiencyWeight, disciplineWeight));
        return new BattleResultService(
                results,
                matches,
                scoreCalculator,
                RatingProgressionCalculator.initialPolicy(),
                snapshots,
                clock,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    BattleResultScheduler battleResultScheduler(
            BattleResultService results,
            @Value("${d3.battle.result-driver.batch-size:25}") int batchSize) {
        return new BattleResultScheduler(results, batchSize);
    }

    @Bean
    BattleEventPublisher battleEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${d3.battle.match-finished-topic:match.finished.v1}") String matchFinishedTopic,
            @Value("${d3.battle.rating-changed-topic:rating.changed.v1}") String ratingChangedTopic) {
        return new KafkaBattleEventPublisher(kafkaTemplate, matchFinishedTopic, ratingChangedTopic);
    }

    @Bean
    BattleOutboxDispatcher battleOutboxDispatcher(
            BattleOutboxStore store, BattleEventPublisher publisher, Clock clock) {
        return new BattleOutboxDispatcher(store, publisher, clock);
    }

    @Bean
    ScheduledBattleOutboxPublisher scheduledBattleOutboxPublisher(BattleOutboxDispatcher dispatcher) {
        return new ScheduledBattleOutboxPublisher(dispatcher);
    }

    @Bean
    BattleDeadlineService battleDeadlineService(
            BattleDeadlineClaimStore claims,
            BattleMatchRepository matches,
            Clock clock,
            PlatformTransactionManager transactionManager,
            BattleSnapshotPublisher snapshots,
            @Qualifier("battleSnapshotFanoutExecutor") Executor snapshotExecutor) {
        return new BattleDeadlineService(
                claims,
                matches,
                clock,
                new TransactionTemplate(transactionManager),
                snapshots,
                snapshotExecutor);
    }

    @Bean
    BattleDeadlineScheduler battleDeadlineScheduler(
            BattleDeadlineService deadlines,
            @Value("${d3.battle.deadline-driver.batch-size:25}") int batchSize) {
        return new BattleDeadlineScheduler(deadlines, batchSize);
    }
}
