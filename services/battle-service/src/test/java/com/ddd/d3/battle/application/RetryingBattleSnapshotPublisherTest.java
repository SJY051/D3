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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void d3Btl002DoesNotClearANewerFailureWhenAnOlderRetrySucceeds() throws Exception {
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BattleSnapshotPublisher delegate = ignored -> {
            switch (calls.incrementAndGet()) {
                case 1, 3 -> throw new IllegalStateException("redis unavailable");
                case 2 -> {
                    retryStarted.countDown();
                    try {
                        if (!releaseRetry.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("retry was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("retry was interrupted", interrupted);
                    }
                }
                default -> {
                    // The retained newer generation succeeds.
                }
            }
        };
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        doReturn(scheduled)
                .when(scheduler)
                .schedule(any(Runnable.class), anyLong(), eq(NANOSECONDS));
        RetryingBattleSnapshotPublisher publisher = new RetryingBattleSnapshotPublisher(
                delegate, scheduler, Duration.ofMillis(250));
        publisher.publish(MATCH_ID);
        ArgumentCaptor<Runnable> firstRetry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(
                firstRetry.capture(), eq(Duration.ofMillis(250).toNanos()), eq(NANOSECONDS));

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> olderRetry = executor.submit(firstRetry.getValue());
            assertEquals(true, retryStarted.await(5, TimeUnit.SECONDS));

            publisher.publish(MATCH_ID);
            releaseRetry.countDown();
            olderRetry.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRetry.countDown();
        }

        assertEquals(1, publisher.pendingCount());
        ArgumentCaptor<Runnable> retries = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2))
                .schedule(retries.capture(), eq(Duration.ofMillis(250).toNanos()), eq(NANOSECONDS));
        retries.getAllValues().get(1).run();
        assertEquals(0, publisher.pendingCount());
    }
}
