package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.ddd.d3.battle.application.BattleAttackService;
import com.ddd.d3.battle.application.BattleMatchViewService;
import com.ddd.d3.battle.application.BattleSubmissionViewService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class BattleWebSocketSessionRegistrySpringContextTest {

    @Test
    void d3Sec001CreatesTheRegistryThroughSpringWithOneAutowiredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BattleMatchViewService.class, () -> mock(BattleMatchViewService.class));
            context.registerBean(BattleAttackService.class, () -> mock(BattleAttackService.class));
            context.registerBean(BattleSubmissionViewService.class, () -> mock(BattleSubmissionViewService.class));
            context.registerBean(BattleDisconnectRetryQueue.class, () -> mock(BattleDisconnectRetryQueue.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(BattleWebSocketSessionRegistry.class);

            context.refresh();

            assertEquals(1, context.getBeansOfType(BattleWebSocketSessionRegistry.class).size());
        }
    }
}
