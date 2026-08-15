package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleSnapshotPublisher;
import com.ddd.d3.battle.application.RetryingBattleSnapshotPublisher;
import com.ddd.d3.battle.infrastructure.redis.RedisBattleSnapshotChannel;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class BattleSnapshotFanoutConfiguration {

    static final String DEFAULT_SNAPSHOT_TOPIC = "d3:battle:snapshot:committed:v2";

    @Bean(name = "battleSnapshotFanoutExecutor")
    ThreadPoolTaskExecutor battleSnapshotFanoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("battle-snapshot-");
        return executor;
    }

    @Bean(name = "battleDisconnectRetryScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService battleDisconnectRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "battle-disconnect-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(name = "battleSnapshotRetryScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService battleSnapshotRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "battle-snapshot-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    RedisBattleSnapshotChannel battleSnapshotChannel(
            StringRedisTemplate redis,
            ObjectProvider<BattleWebSocketSessionRegistry> sessions,
            @Qualifier("battleSnapshotFanoutExecutor") Executor executor,
            @Value("${d3.battle.snapshot-topic:" + DEFAULT_SNAPSHOT_TOPIC + "}") String topic) {
        return new RedisBattleSnapshotChannel(
                redis, matchId -> sessions.getObject().publish(matchId), executor, topic);
    }

    @Bean
    @Primary
    BattleSnapshotPublisher battleSnapshotPublisher(
            RedisBattleSnapshotChannel channel,
            @Qualifier("battleSnapshotRetryScheduler") ScheduledExecutorService scheduler,
            @Value("${d3.battle.snapshot-retry-delay:250ms}") Duration retryDelay) {
        return new RetryingBattleSnapshotPublisher(channel, scheduler, retryDelay);
    }

    @Bean
    RedisMessageListenerContainer battleSnapshotListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisBattleSnapshotChannel channel,
            @Qualifier("battleSnapshotFanoutExecutor") Executor executor,
            @Value("${d3.battle.snapshot-topic:" + DEFAULT_SNAPSHOT_TOPIC + "}") String topic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(executor);
        container.addMessageListener(channel, new ChannelTopic(topic));
        return container;
    }
}
