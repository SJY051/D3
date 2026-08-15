package com.ddd.d3.battle.infrastructure.redis;

import com.ddd.d3.battle.application.RankedQueueConflictException;
import com.ddd.d3.battle.application.RankedQueueStore;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisRankedQueueStore implements RankedQueueStore {

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_ENTRY = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[2] then
                redis.call('DEL', KEYS[1])
                return redis.call('ZREM', KEYS[2], ARGV[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisRankedQueueStore(
            StringRedisTemplate redis,
            @Value("${d3.battle.ranked-queue.prefix:d3:ranked:v1}") String prefix) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        this.prefix = prefix;
    }

    @Override
    public Optional<Lease> tryAcquire(RankedMatchmaker.Language language, Duration leaseTtl) {
        Objects.requireNonNull(language, "language must not be null");
        requirePositive(leaseTtl, "leaseTtl");
        String lockKey = lockKey(language);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, leaseTtl);
        return Boolean.TRUE.equals(acquired)
                ? Optional.of(new RedisLease(language, lockKey, token))
                : Optional.empty();
    }

    private final class RedisLease implements Lease {
        private final RankedMatchmaker.Language language;
        private final String lockKey;
        private final String lockToken;
        private boolean closed;

        private RedisLease(RankedMatchmaker.Language language, String lockKey, String lockToken) {
            this.language = language;
            this.lockKey = lockKey;
            this.lockToken = lockToken;
        }

        @Override
        public RankedMatchmaker.Entry enqueue(Ticket ticket, Duration entryTtl) {
            requireOpen();
            Objects.requireNonNull(ticket, "ticket must not be null");
            requirePositive(entryTtl, "entryTtl");
            if (ticket.language() != language) {
                throw new IllegalArgumentException("ticket language does not match the queue lease");
            }

            String playerKey = playerKey(ticket.playerId());
            String current = redis.opsForValue().get(playerKey);
            if (current != null) {
                return existingEntry(ticket, current, entryTtl);
            }

            Long sequence = redis.opsForValue().increment(sequenceKey(language));
            if (sequence == null) {
                throw new IllegalStateException("Redis did not allocate a queue sequence");
            }
            RankedMatchmaker.Entry created = new RankedMatchmaker.Entry(
                    ticket.ticketId(),
                    ticket.playerId(),
                    ticket.language(),
                    ticket.publicRating(),
                    ticket.enqueuedAt(),
                    sequence);
            String encoded = encode(created);
            Boolean inserted = redis.opsForValue().setIfAbsent(playerKey, encoded, entryTtl);
            if (!Boolean.TRUE.equals(inserted)) {
                String raced = redis.opsForValue().get(playerKey);
                if (raced == null) {
                    throw new IllegalStateException("Ranked queue entry changed during enqueue");
                }
                return existingEntry(ticket, raced, entryTtl);
            }

            addToLanguageQueue(created, entryTtl);
            redis.expire(sequenceKey(language), entryTtl);
            return created;
        }

        @Override
        public List<RankedMatchmaker.Entry> activeEntries() {
            requireOpen();
            String queueKey = queueKey(language);
            Set<String> playerIds = redis.opsForZSet().range(queueKey, 0, -1);
            if (playerIds == null || playerIds.isEmpty()) {
                return List.of();
            }

            List<RankedMatchmaker.Entry> entries = new ArrayList<>(playerIds.size());
            for (String playerIdValue : playerIds) {
                UUID playerId = UUID.fromString(playerIdValue);
                String encoded = redis.opsForValue().get(playerKey(playerId));
                if (encoded == null) {
                    redis.opsForZSet().remove(queueKey, playerIdValue);
                    continue;
                }
                RankedMatchmaker.Entry entry = decode(playerId, encoded);
                if (entry.language() != language) {
                    redis.opsForZSet().remove(queueKey, playerIdValue);
                    continue;
                }
                entries.add(entry);
            }
            return List.copyOf(entries);
        }

        @Override
        public void remove(Collection<RankedMatchmaker.Entry> entries) {
            requireOpen();
            Objects.requireNonNull(entries, "entries must not be null");
            for (RankedMatchmaker.Entry entry : entries) {
                if (entry.language() != language) {
                    throw new IllegalArgumentException("entry language does not match the queue lease");
                }
                redis.execute(
                        REMOVE_ENTRY,
                        List.of(playerKey(entry.playerId()), queueKey(language)),
                        entry.playerId().toString(),
                        encode(entry));
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            redis.execute(COMPARE_AND_DELETE, List.of(lockKey), lockToken);
        }

        private RankedMatchmaker.Entry existingEntry(Ticket ticket, String encoded, Duration entryTtl) {
            RankedMatchmaker.Entry existing = decode(ticket.playerId(), encoded);
            if (!existing.ticketId().equals(ticket.ticketId()) || existing.language() != language) {
                throw new RankedQueueConflictException("player already has another active ranked ticket");
            }
            redis.expire(playerKey(ticket.playerId()), entryTtl);
            addToLanguageQueue(existing, entryTtl);
            redis.expire(sequenceKey(language), entryTtl);
            return existing;
        }

        private void addToLanguageQueue(RankedMatchmaker.Entry entry, Duration entryTtl) {
            String queueKey = queueKey(language);
            redis.opsForZSet().add(queueKey, entry.playerId().toString(), entry.sequence());
            redis.expire(queueKey, entryTtl);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("ranked queue lease is closed");
            }
        }
    }

    private static String encode(RankedMatchmaker.Entry entry) {
        return String.join(
                "|",
                entry.ticketId().toString(),
                entry.language().name(),
                Integer.toString(entry.publicRating()),
                Long.toString(entry.enqueuedAt().toEpochMilli()),
                Long.toString(entry.sequence()));
    }

    private static RankedMatchmaker.Entry decode(UUID playerId, String encoded) {
        String[] fields = encoded.split("\\|", -1);
        if (fields.length != 5) {
            throw new IllegalStateException("Malformed ranked queue entry");
        }
        try {
            return new RankedMatchmaker.Entry(
                    UUID.fromString(fields[0]),
                    playerId,
                    RankedMatchmaker.Language.valueOf(fields[1]),
                    Integer.parseInt(fields[2]),
                    Instant.ofEpochMilli(Long.parseLong(fields[3])),
                    Long.parseLong(fields[4]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Malformed ranked queue entry", exception);
        }
    }

    private String lockKey(RankedMatchmaker.Language language) {
        return prefix + ":lock:" + language.name();
    }

    private String queueKey(RankedMatchmaker.Language language) {
        return prefix + ":queue:" + language.name();
    }

    private String sequenceKey(RankedMatchmaker.Language language) {
        return prefix + ":sequence:" + language.name();
    }

    private String playerKey(UUID playerId) {
        return prefix + ":player:" + playerId;
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
