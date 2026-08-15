package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.BattleJudgeGateway;
import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBattleJudgeReferenceStore implements BattleJudgeReferenceStore {
    private static final Set<String> SUPPORTED_LANGUAGES =
            Set.of("C", "CPP", "JAVA", "PYTHON3", "JAVASCRIPT", "TYPESCRIPT");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcBattleJudgeReferenceStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = JdbcClient.create(dataSource);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public SubmissionContext lockSubmissionContext(
            UUID matchId,
            UUID playerId,
            long connectionGeneration,
            BattleJudgeGateway.Mode mode) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(mode, "mode");
        ContextRow context = jdbc.sql("""
                        select m.status, m.aggregate_version, p.id as problem_id, p.version as problem_version,
                               mp.language_key, mp.connection_state, mp.connection_generation
                        from match m
                        join problem p on p.id = m.problem_id
                        join match_player mp on mp.match_id = m.id and mp.user_id = :playerId
                        where m.id = :matchId
                        for update of m
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query((resultSet, rowNumber) -> new ContextRow(
                        resultSet.getString("status"),
                        resultSet.getLong("aggregate_version"),
                        resultSet.getObject("problem_id", UUID.class),
                        resultSet.getInt("problem_version"),
                        resultSet.getString("language_key"),
                        resultSet.getString("connection_state"),
                        resultSet.getLong("connection_generation")))
                .optional()
                .orElseThrow(BattleMatchNotFoundException::new);
        if (!"RUNNING".equals(context.status())) {
            throw new IllegalStateException("Judge commands require a RUNNING match");
        }
        if (!"CONNECTED".equals(context.connectionState())
                || context.connectionGeneration() != connectionGeneration) {
            throw new IllegalStateException("WebSocket connection is not authoritative");
        }
        if (mode == BattleJudgeGateway.Mode.SUBMIT) {
            Optional<String> activeSubmit = jdbc.sql("""
                            select last_judge_status
                            from judge_job_reference
                            where match_id = :matchId
                              and player_user_id = :playerId
                              and mode = 'SUBMIT'
                              and last_judge_status in ('QUEUED', 'RUNNING', 'ACCEPTED')
                            order by case when last_judge_status = 'ACCEPTED' then 0 else 1 end
                            limit 1
                            """)
                    .param("matchId", matchId)
                    .param("playerId", playerId)
                    .query(String.class)
                    .optional();
            if (activeSubmit.isPresent()) {
                throw new IllegalStateException("A SUBMIT result is already pending or accepted");
            }
        }
        int nextAttempt = jdbc.sql("""
                        select coalesce(max(attempt_number), 0) + 1
                        from judge_job_reference
                        where match_id = :matchId and player_user_id = :playerId and mode = 'SUBMIT'
                        """)
                .param("matchId", matchId)
                .param("playerId", playerId)
                .query(Integer.class)
                .single();
        String language = context.language().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new IllegalStateException("Unsupported battle language: " + context.language());
        }
        return new SubmissionContext(
                context.problemId(), context.problemVersion(), language, nextAttempt, context.aggregateVersion());
    }

    @Override
    public Optional<Reference> findByCommandId(UUID commandId) {
        Objects.requireNonNull(commandId, "commandId");
        return jdbc.sql("""
                        select submission_id, match_id, player_user_id, mode, command_id, attempt_number,
                               last_judge_status, evidence_version, accepted_at, last_result_at
                        from judge_job_reference
                        where command_id = :commandId
                        """)
                .param("commandId", commandId)
                .query(this::reference)
                .optional();
    }

    @Override
    public void record(Reference reference) {
        Objects.requireNonNull(reference, "reference");
        int inserted = jdbc.sql("""
                        insert into judge_job_reference (
                            submission_id, match_id, player_user_id, mode, command_id, attempt_number,
                            last_judge_status, evidence_version, accepted_at, last_result_at
                        ) values (
                            :submissionId, :matchId, :playerId, :mode, :commandId, :attemptNumber,
                            :status, :evidenceVersion, :acceptedAt, :lastResultAt
                        )
                        """)
                .param("submissionId", reference.submissionId())
                .param("matchId", reference.matchId())
                .param("playerId", reference.playerId())
                .param("mode", reference.mode().name())
                .param("commandId", reference.commandId())
                .param("attemptNumber", reference.attemptNumber())
                .param("status", reference.status())
                .param("evidenceVersion", reference.evidenceVersion())
                .param("acceptedAt", Timestamp.from(reference.acceptedAt()))
                .param("lastResultAt", timestamp(reference.lastResultAt()))
                .update();
        if (inserted != 1) throw new IllegalStateException("Judge submission reference was not inserted");
        if (reference.mode() == BattleJudgeGateway.Mode.SUBMIT) {
            jdbc.sql("""
                            update match_player
                            set attempts = greatest(attempts, :attemptNumber)
                            where match_id = :matchId and user_id = :playerId
                            """)
                    .param("attemptNumber", reference.attemptNumber())
                    .param("matchId", reference.matchId())
                    .param("playerId", reference.playerId())
                    .update();
        }
    }

    @Override
    public boolean receiveJudgedEvent(JudgedEvent event) {
        Objects.requireNonNull(event, "event");
        return jdbc.sql("""
                        insert into inbox_event (
                            event_id, event_type, aggregate_id, aggregate_version, received_at, applied_at
                        ) values (
                            :eventId, 'submission.judged', :submissionId, :aggregateVersion, :receivedAt, null
                        )
                        on conflict do nothing
                        """)
                .param("eventId", event.eventId())
                .param("submissionId", event.submissionId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update() == 1;
    }

    @Override
    public List<PendingJudgedEvent> findProcessablePending(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return jdbc.sql("""
                        select inbox.event_id, inbox.aggregate_id
                        from inbox_event inbox
                        join judge_job_reference ref on ref.submission_id = inbox.aggregate_id
                        where inbox.event_type = 'submission.judged' and inbox.applied_at is null
                        order by inbox.received_at, inbox.event_id
                        limit :limit
                        """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PendingJudgedEvent(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getObject("aggregate_id", UUID.class)))
                .list();
    }

    @Override
    public Optional<Reference> lockPendingReference(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        return jdbc.sql("""
                        select ref.submission_id, ref.match_id, ref.player_user_id, ref.mode, ref.command_id,
                               ref.attempt_number, ref.last_judge_status, ref.evidence_version,
                               ref.accepted_at, ref.last_result_at
                        from inbox_event inbox
                        join judge_job_reference ref on ref.submission_id = inbox.aggregate_id
                        where inbox.event_id = :eventId
                          and inbox.event_type = 'submission.judged'
                          and inbox.applied_at is null
                        for update of inbox
                        """)
                .param("eventId", eventId)
                .query(this::reference)
                .optional();
    }

    @Override
    public void recordEvidence(UUID eventId, Evidence evidence) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(evidence, "evidence");
        Reference reference = lockPendingReference(eventId)
                .orElseThrow(() -> new IllegalStateException("Judged event is no longer pending"));
        if (!reference.submissionId().equals(evidence.submissionId())) {
            throw new IllegalStateException("Judged evidence submissionId mismatch");
        }
        int updated = jdbc.sql("""
                        update judge_job_reference
                        set last_judge_status = :status,
                            evidence_version = :evidenceVersion,
                            last_result_at = :completedAt,
                            passed_count = :passedCount,
                            total_count = :totalCount,
                            runtime_measurements = cast(:runtimeMeasurements as jsonb),
                            adapter_version = :adapterVersion,
                            runtime_version = :runtimeVersion
                        where submission_id = :submissionId
                        """)
                .param("status", evidence.status())
                .param("evidenceVersion", evidence.evidenceVersion())
                .param("completedAt", Timestamp.from(evidence.completedAt()))
                .param("passedCount", evidence.passedCount())
                .param("totalCount", evidence.totalCount())
                .param("runtimeMeasurements", json(evidence.runtimeMeasurements()))
                .param("adapterVersion", evidence.adapterVersion())
                .param("runtimeVersion", evidence.runtimeVersion())
                .param("submissionId", evidence.submissionId())
                .update();
        if (updated != 1) throw new IllegalStateException("Judge evidence reference was not updated");
        int applied = jdbc.sql("""
                        update inbox_event
                        set applied_at = :completedAt
                        where event_id = :eventId and applied_at is null
                        """)
                .param("completedAt", Timestamp.from(evidence.completedAt()))
                .param("eventId", eventId)
                .update();
        if (applied != 1) throw new IllegalStateException("Judged event was not applied exactly once");
    }

    private Reference reference(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lastResultAt = resultSet.getTimestamp("last_result_at");
        return new Reference(
                resultSet.getObject("submission_id", UUID.class),
                resultSet.getObject("match_id", UUID.class),
                resultSet.getObject("player_user_id", UUID.class),
                BattleJudgeGateway.Mode.valueOf(resultSet.getString("mode")),
                resultSet.getObject("command_id", UUID.class),
                resultSet.getObject("attempt_number", Integer.class),
                resultSet.getString("last_judge_status"),
                resultSet.getString("evidence_version"),
                resultSet.getTimestamp("accepted_at").toInstant(),
                lastResultAt == null ? null : lastResultAt.toInstant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Runtime measurements could not be serialized", exception);
        }
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private record ContextRow(
            String status,
            long aggregateVersion,
            UUID problemId,
            int problemVersion,
            String language,
            String connectionState,
            long connectionGeneration) {}
}
