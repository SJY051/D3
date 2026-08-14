package com.ddd.d3.judge.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JudgeOutboxDispatcherTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-13T12:00:02Z");

    @Test
    void d3Jdg001MarksOnlySuccessfullyPublishedEvents() {
        PendingJudgeEvent first = event("11111111-1111-4111-8111-111111111111");
        PendingJudgeEvent second = event("22222222-2222-4222-8222-222222222222");
        RecordingStore store = new RecordingStore(List.of(first, second));
        List<UUID> published = new ArrayList<>();
        JudgeOutboxDispatcher dispatcher = new JudgeOutboxDispatcher(
                store,
                event -> {
                    published.add(event.eventId());
                    if (event == second) {
                        throw new IllegalStateException("broker unavailable");
                    }
                },
                Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, dispatcher::dispatchBatch);
        assertEquals(List.of(first.eventId(), second.eventId()), published);
        assertEquals(List.of(first.eventId()), store.marked);
    }

    @Test
    void d3Jdg001DispatchesABoundedBatchInStoreOrder() {
        PendingJudgeEvent first = event("11111111-1111-4111-8111-111111111111");
        PendingJudgeEvent second = event("22222222-2222-4222-8222-222222222222");
        RecordingStore store = new RecordingStore(List.of(first, second));
        List<UUID> published = new ArrayList<>();
        JudgeOutboxDispatcher dispatcher = new JudgeOutboxDispatcher(
                store,
                event -> published.add(event.eventId()),
                Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC));

        assertEquals(2, dispatcher.dispatchBatch());
        assertEquals(published, store.marked);
        assertEquals(20, store.requestedMaximum);
    }

    private static PendingJudgeEvent event(String id) {
        return new PendingJudgeEvent(UUID.fromString(id), "aggregate", "{}");
    }

    private static final class RecordingStore implements JudgeOutboxStore {
        private final List<PendingJudgeEvent> events;
        private final List<UUID> marked = new ArrayList<>();
        private int requestedMaximum;

        private RecordingStore(List<PendingJudgeEvent> events) {
            this.events = events;
        }

        @Override
        public List<PendingJudgeEvent> loadUnpublished(int maximumCount) {
            requestedMaximum = maximumCount;
            return events;
        }

        @Override
        public void markPublished(UUID eventId, Instant publishedAt) {
            assertEquals(PUBLISHED_AT, publishedAt);
            marked.add(eventId);
        }
    }
}
