package io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalStoreTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final ProposalTelemetry telemetry = mock(ProposalTelemetry.class);
    private final Instant now = Instant.parse("2026-11-01T12:00:00Z");
    private final StoredProposal saved = new StoredProposal("p", "AUTO", "j", "c", "e", now, false,
            "00-01234567890123456789012345678901-0123456789012345-01", "vendor=value");
    private final ProposalStore store = new ProposalStore(jdbc, manager, telemetry);

    @BeforeEach
    void transactionAndObservation() {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(telemetry.registration()).thenReturn(Observation.NOOP);
        when(telemetry.traceHeaders()).thenReturn(Map.of("traceparent", saved.traceparent(), "tracestate", saved.tracestate()));
        when(jdbc.queryForObject(startsWith(ProposalStore.SELECT), any(RowMapper.class), any())).thenReturn(saved);
    }

    @Test
    void commitsProposalAndOutboxBeforeRecordingSuccess() {
        when(jdbc.update(startsWith("INSERT INTO proposals"), any(), any(), any(), any(), any())).thenReturn(1);
        assertSame(saved, store.register("request-key", "AUTO", now));
        var hash = ArgumentCaptor.forClass(String.class);
        var proposalId = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("INSERT INTO proposals"), proposalId.capture(), hash.capture(),
                eq("AUTO"), any(), any());
        assertDoesNotThrow(() -> UUID.fromString(proposalId.getValue()));
        assertTrue(hash.getValue().matches("[0-9a-f]{64}"));
        assertNotEquals("request-key", hash.getValue());
        verify(jdbc).update(startsWith("INSERT INTO proposal_outbox"), any(), eq(proposalId.getValue()),
                eq(Timestamp.from(now)), eq(saved.traceparent()), eq(saved.tracestate()));
        var order = inOrder(manager, telemetry);
        order.verify(manager).commit(status);
        order.verify(telemetry).registered(Observation.NOOP, saved, true);
        verify(manager, never()).rollback(any());
    }

    @Test
    void replayUsesStoredIdentityWithoutAnotherOutboxInsert() {
        assertSame(saved, store.register("same-key", "AUTO", now));
        store.register("same-key", "AUTO", now.plusSeconds(1));
        var hash = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(startsWith("INSERT INTO proposals"), any(), hash.capture(), any(), any(), any());
        assertEquals(hash.getAllValues().getFirst(), hash.getAllValues().getLast());
        verify(jdbc, never()).update(startsWith("INSERT INTO proposal_outbox"), any(), any(), any(), any(), any());
        verify(telemetry, times(2)).registered(Observation.NOOP, saved, false);
    }

    @Test
    void differentContentRollsBackWithoutSuccessMetric() {
        assertThrows(IdempotencyConflictException.class, () -> store.register("same-key", "HOME", now));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        verify(telemetry, never()).registered(any(), any(), anyBoolean());
        verify(telemetry).registrationFailed(eq(Observation.NOOP), any(IdempotencyConflictException.class));
    }

    @Test
    void missingRowAndFailedOutboxInsertBothRollBack() {
        when(jdbc.queryForObject(startsWith(ProposalStore.SELECT), any(RowMapper.class), any())).thenReturn(null);
        assertThrows(IdempotencyConflictException.class, () -> store.register("key", "AUTO", now));
        when(jdbc.update(startsWith("INSERT INTO proposals"), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(startsWith("INSERT INTO proposal_outbox"), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database down"));
        assertThrows(IllegalStateException.class, () -> store.register("key", "AUTO", now));
        verify(manager, times(2)).rollback(status);
        verify(telemetry, never()).registered(any(), any(), anyBoolean());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void registrationStopsItsObservationAfterCommitOrRollback(boolean commit) {
        var registry = io.micrometer.observation.ObservationRegistry.create();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        registry.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        var observation = Observation.start("registration", registry);
        when(telemetry.registration()).thenReturn(observation);
        if (commit) {
            assertSame(saved, store.register("key", "AUTO", now));
            verify(manager).commit(status);
        } else {
            assertThrows(IdempotencyConflictException.class, () -> store.register("key", "HOME", now));
            verify(manager).rollback(status);
        }
        assertNull(registry.getCurrentObservation());
        assertEquals(1, meters.get("registration").timer().count());
    }
    @Test
    void missingHashAlgorithmFailsBeforeWritingAndRollsBackTheTransaction() {
        var unavailable = new java.security.NoSuchAlgorithmException("unavailable provider");
        try (var digest = mockStatic(java.security.MessageDigest.class)) {
            digest.when(() -> java.security.MessageDigest.getInstance("SHA-256")).thenThrow(unavailable);
            var failure = assertThrows(IllegalStateException.class, () -> store.register("key", "AUTO", now));
            assertSame(unavailable, failure.getCause());
            verify(manager).rollback(status);
            verify(manager, never()).commit(any());
            verifyNoInteractions(jdbc);
        }
    }
    @Test
    void publicationStatusRequiresPositiveDatabaseValue() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("p"))).thenReturn(false, true, null);
        assertFalse(store.isPublished("p"));
        assertTrue(store.isPublished("p"));
        assertFalse(store.isPublished("p"));
    }

    @Test
    void rowMappingPreservesTraceIdentityAndPublicationState() throws Exception {
        var row = mock(ResultSet.class);
        for (var field : Map.of("proposal_id", "p", "product_key", "AUTO", "journey_id", "j",
                "correlation_id", "c", "event_id", "e", "traceparent", saved.traceparent(),
                "tracestate", saved.tracestate()).entrySet()) {
            when(row.getString(field.getKey())).thenReturn(field.getValue());
        }
        when(row.getTimestamp("occurred_at")).thenReturn(Timestamp.from(now));
        assertEquals(saved, StoredProposal.read(row, 0));
        when(row.getTimestamp("published_at")).thenReturn(Timestamp.from(now));
        var published = StoredProposal.read(row, 0);
        assertTrue(published.published());
        assertEquals("p", published.response().proposalId());
        assertEquals("j", published.response().journeyId());
        assertEquals("c", published.response().correlationId());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void committedAndDuplicateLogsCarryBusinessContextAndPreciseOutcome(boolean created) {
        when(jdbc.update(startsWith("INSERT INTO proposals"), any(), any(), any(), any(), any()))
                .thenReturn(created ? 1 : 0);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProposalStore.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            store.register("key", "AUTO", now);
            var fields = appender.list.getLast().getKeyValuePairs().stream().collect(
                    java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertEquals(created ? "success" : "replay", fields.get("outcome"));
            assertEquals("p", fields.get("businessId"));
            assertEquals("none", fields.get("error_code"));
            assertEquals("AUTO", fields.get("product_key"));
            assertEquals("PROPOSAL", fields.get("journey_type"));
            assertNotNull(fields.get("journeyId"));
            assertNotNull(fields.get("correlationId"));
            assertNotNull(fields.get("eventId"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

}
