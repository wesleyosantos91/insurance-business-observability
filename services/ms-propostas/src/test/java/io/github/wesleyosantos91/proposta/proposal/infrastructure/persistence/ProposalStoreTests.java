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
}
