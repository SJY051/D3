package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.infrastructure.redis.RedisBattleSnapshotChannel;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class BattleWebSocketConfiguration implements WebSocketConfigurer {

    static final String MATCH_PATH = "/ws/v1/battle/matches/*";
    static final String DEFAULT_SNAPSHOT_TOPIC = "d3:battle:snapshot:committed:v2";
    private final BattleWebSocketHandler handler;
    private final BattleWebSocketHandshakeInterceptor handshakeInterceptor;
    private final String webOrigin;

    BattleWebSocketConfiguration(
            BattleWebSocketHandler handler,
            BattleWebSocketHandshakeInterceptor handshakeInterceptor,
            @Value("${d3.web-origin:http://localhost:5173}") String webOrigin) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.handshakeInterceptor = Objects.requireNonNull(
                handshakeInterceptor, "handshakeInterceptor must not be null");
        this.webOrigin = requireOrigin(webOrigin);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, MATCH_PATH)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(webOrigin);
    }

    @Bean(name = "battleSnapshotFanoutExecutor")
    ThreadPoolTaskExecutor battleSnapshotFanoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("battle-snapshot-");
        return executor;
    }

    @Bean
    RedisBattleSnapshotChannel battleSnapshotChannel(
            StringRedisTemplate redis,
            @Qualifier("battleSnapshotFanoutExecutor") Executor executor,
            @Value("${d3.battle.snapshot-topic:" + DEFAULT_SNAPSHOT_TOPIC + "}") String topic) {
        return new RedisBattleSnapshotChannel(redis, handler::publishLocal, executor, topic);
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

    private static String requireOrigin(String origin) {
        Objects.requireNonNull(origin, "webOrigin must not be null");
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("webOrigin must be one exact HTTP(S) origin", exception);
        }
        boolean supportedScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!supportedScheme
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getQuery() != null
                || uri.getFragment() != null
                || origin.contains("*")) {
            throw new IllegalArgumentException("webOrigin must be one exact HTTP(S) origin");
        }
        return origin;
    }
}
