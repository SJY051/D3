package com.ddd.d3.battle.application;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetryingBattleSnapshotPublisherTest {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void d3Btl002RetainsACommittedSnapshotUntilCrossInstanceFanoutRecovers() {
        BattleSnapshotPublisher delegate = mock(BattleSnapshotPublisher.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .doThrow(new IllegalStateException("redis still unavailable"))
                .doNothing()
                .when(delegate)
                .publish(MATCH_ID);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled)
                .when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(NANOSECONDS));
        RetryingBattleSnapshotPublisher publisher = new RetryingBattleSnapshotPublisher(
                delegate, scheduler, Duration.ofMillis(250));

        publisher.publish(MATCH_ID);

        assertEquals(1, publisher.pendingCount());
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(retry.capture(), eq(Duration.ofMillis(250).toNanos()), eq(NANOSECONDS));

        retry.getValue().run();

        assertEquals(1, publisher.pendingCount());
        ArgumentCaptor<Runnable> retries = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2))
                .schedule(retries.capture(), eq(Duration.ofMillis(250).toNanos()), eq(NANOSECONDS));
        retries.getAllValues().get(1).run();

        assertEquals(0, publisher.pendingCount());
        verify(delegate, times(3)).publish(MATCH_ID);
    }
}
