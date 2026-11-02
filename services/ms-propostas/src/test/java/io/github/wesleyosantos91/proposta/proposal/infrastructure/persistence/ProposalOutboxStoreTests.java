package io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalOutboxStoreTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final ProposalOutboxStore store = new ProposalOutboxStore(jdbc, manager);
    private final ProposalOutboxStore.Claim claim = new ProposalOutboxStore.Claim(
            new StoredProposal("p", "AUTO", "j", "c", "e", Instant.now(), false, null, null),
            UUID.randomUUID(), 2);

    @BeforeEach
    void transaction() {
        when(manager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void emptyQueueCommitsShortIsolatedClaimTransaction() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        assertTrue(store.claim(Duration.ofSeconds(30)).isEmpty());
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getValue().getIsolationLevel());
        assertEquals(5, definition.getValue().getTimeout());
        verify(manager).commit(status);
    }

    @Test
    void claimMapsReturnedEventAndCommitsBeforeReturning() throws Exception {
        var row = mock(ResultSet.class);
        when(row.getTimestamp("occurred_at")).thenReturn(Timestamp.from(claim.proposal().occurredAt()));
        when(row.getString("event_id")).thenReturn("same-event");
        when(row.getInt("attempts")).thenReturn(7);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), eq(30000L))).thenAnswer(invocation -> {
            RowMapper<ProposalOutboxStore.Claim> mapper = invocation.getArgument(1);
            var mapped = mapper.mapRow(row, 0);
            assertEquals(invocation.getArgument(2), mapped.token());
            return List.of(mapped);
        });
        var result = store.claim(Duration.ofSeconds(30)).orElseThrow();
        assertEquals("same-event", result.proposal().eventId());
        assertEquals(7, result.attempts());
        assertNotNull(result.token());
        verify(manager).commit(status);
    }

    @Test
    void markingPublishedAndRetryReturnWhetherFencingAllowedUpdate() {
        when(jdbc.update(contains("SET published_at"), eq("e"), eq(claim.token()))).thenReturn(1, 0);
        assertTrue(store.published(claim));
        assertFalse(store.published(claim));
        when(jdbc.update(contains("SET next_attempt_at"), eq(1500L), eq("TimeoutException"),
                eq("e"), eq(claim.token()))).thenReturn(1, 0);
        assertTrue(store.retry(claim, Duration.ofMillis(1500), "TimeoutException"));
        assertFalse(store.retry(claim, Duration.ofMillis(1500), "TimeoutException"));
        verify(manager, times(4)).commit(status);
    }

    @Test
    void databaseFailureRollsBackClaimWithoutReportingSuccess() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenThrow(new IllegalStateException("connection failed"));
        assertThrows(IllegalStateException.class, () -> store.claim(Duration.ofSeconds(30)));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }
}
