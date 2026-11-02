package io.github.wesleyosantos91.cadastro.proposal.application;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessProposalCreatedUseCaseTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProcessProposalCreatedUseCase useCase = new ProcessProposalCreatedUseCase(jdbc);

    @Test
    void persistsANewReceiptAfterReservingTheInbox() {
        when(jdbc.update(startsWith("INSERT INTO proposal_inbox"), eq("event"), anyString())).thenReturn(1);
        assertTrue(useCase.process(proposal("1.0.0")));
        verify(jdbc).update(startsWith("INSERT INTO received_proposals"), eq("event"), eq("proposal"),
                eq("AUTO"), eq("journey"), eq("root"), eq("correlation"), any());
    }

    @Test
    void identicalRedeliveryDoesNotRepeatTheReceipt() {
        var fingerprint = ArgumentCaptor.forClass(String.class);
        when(jdbc.update(startsWith("INSERT INTO proposal_inbox"), eq("event"), fingerprint.capture())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("event")))
                .thenAnswer(ignored -> fingerprint.getValue());
        assertFalse(useCase.process(proposal("1.0.0")));
        verify(jdbc, never()).update(startsWith("INSERT INTO received_proposals"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reusingAnEventIdWithDifferentMetadataFailsClosed() {
        var captured = ArgumentCaptor.forClass(String.class);
        when(jdbc.update(startsWith("INSERT INTO proposal_inbox"), eq("event"), captured.capture())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("event")))
                .thenAnswer(ignored -> captured.getAllValues().getFirst());
        assertFalse(useCase.process(proposal("1.0.0")));
        assertThrows(IllegalArgumentException.class, () -> useCase.process(proposal("1.1.0")));
        verify(jdbc, never()).update(startsWith("INSERT INTO received_proposals"),
                any(), any(), any(), any(), any(), any(), any());
    }

    private ReceivedProposal proposal(String version) {
        return new ReceivedProposal("event", "ProposalCreated", version, "PROPOSAL",
                "proposal", "AUTO", "journey", "root", "correlation", null, null,
                Instant.parse("2026-11-01T12:00:00Z"));
    }
}
