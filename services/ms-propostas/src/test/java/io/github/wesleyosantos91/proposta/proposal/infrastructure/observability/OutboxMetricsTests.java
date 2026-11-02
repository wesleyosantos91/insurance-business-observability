package io.github.wesleyosantos91.proposta.proposal.infrastructure.observability;

import java.sql.ResultSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxMetricsTests {
    @Test
    void exportsCachedSnapshotAndMakesDatabaseFailureVisible() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var meters = new SimpleMeterRegistry();
        var metrics = new OutboxMetrics(jdbc, new MockEnvironment(), meters);
        assertTrue(Double.isNaN(meters.get("insurance.outbox.pending").gauge().value()));
        assertEquals(0, meters.get("insurance.outbox.snapshot.available").gauge().value());
        var row = mock(ResultSet.class);
        when(row.getDouble("pending")).thenReturn(4d);
        when(row.getDouble("age")).thenReturn(12d);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class)))
                .thenAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0));
        metrics.refresh();
        assertEquals(4, meters.get("insurance.outbox.pending").gauge().value());
        assertEquals(12, meters.get("insurance.outbox.oldest.age").gauge().value());
        assertEquals(1, meters.get("insurance.outbox.snapshot.available").gauge().value());
        verify(jdbc).queryForObject(anyString(), any(RowMapper.class));
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenThrow(new IllegalStateException("down"));
        metrics.refresh();
        assertTrue(Double.isNaN(meters.get("insurance.outbox.pending").gauge().value()));
        assertTrue(Double.isNaN(meters.get("insurance.outbox.oldest.age").gauge().value()));
        assertEquals(0, meters.get("insurance.outbox.snapshot.available").gauge().value());
    }

    @Test
    void trainingNeverTouchesDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var metrics = new OutboxMetrics(jdbc, new MockEnvironment()
                .withProperty("insurance.persistence.initialize", "false"), new SimpleMeterRegistry());
        metrics.refresh();
        verifyNoInteractions(jdbc);
    }
}
