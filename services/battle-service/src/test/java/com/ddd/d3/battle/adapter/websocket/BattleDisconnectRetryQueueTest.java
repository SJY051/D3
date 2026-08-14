package com.ddd.d3.battle.adapter.websocket;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ddd.d3.battle.application.BattleConnectionService;
import com.ddd.d3.battle.application.OptimisticMatchConflictException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BattleDisconnectRetryQueueTest {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void d3Btl002RetainsAClosedGenerationUntilAnOptimisticDisconnectCommits() {
        BattleConnectionService connections = mock(BattleConnectionService.class);
        doThrow(new OptimisticMatchConflictException())
                .doThrow(new OptimisticMatchConflictException())
                .doNothing()
                .when(connections)
                .disconnected(MATCH_ID, PLAYER_ID, 7);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled)
                .when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(NANOSECONDS));
        BattleDisconnectRetryQueue retries = new BattleDisconnectRetryQueue(
                connections, scheduler, Duration.ofMillis(5));

        retries.disconnect(MATCH_ID, PLAYER_ID, 7);

        assertEquals(1, retries.pendingCount());
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(tasks.capture(), eq(Duration.ofMillis(5).toNanos()), eq(NANOSECONDS));

        tasks.getAllValues().get(0).run();
        verify(scheduler, times(2))
                .schedule(tasks.capture(), eq(Duration.ofMillis(5).toNanos()), eq(NANOSECONDS));
        assertEquals(1, retries.pendingCount());

        tasks.getAllValues().get(1).run();
        assertEquals(0, retries.pendingCount());
        verify(connections, times(3)).disconnected(MATCH_ID, PLAYER_ID, 7);
    }
}
