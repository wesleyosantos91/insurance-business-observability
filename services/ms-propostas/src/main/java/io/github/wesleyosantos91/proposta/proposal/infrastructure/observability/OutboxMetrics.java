package io.github.wesleyosantos91.proposta.proposal.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OutboxMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxMetrics.class);
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private volatile Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, 0);

    OutboxMetrics(JdbcTemplate jdbc, Environment environment, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.environment = environment;
        Gauge.builder("insurance.outbox.pending", this, metrics -> metrics.snapshot.pending()).register(meters);
        Gauge.builder("insurance.outbox.oldest.age", this, metrics -> metrics.snapshot.ageSeconds())
                .baseUnit("seconds").register(meters);
        Gauge.builder("insurance.outbox.snapshot.available", this, metrics -> metrics.snapshot.available()).register(meters);
    }

    @Scheduled(fixedDelayString = "${insurance.outbox.metrics-delay:10000}", initialDelay = 1000)
    void refresh() {
        if (!environment.getProperty("insurance.persistence.initialize", Boolean.class, true)) {
            return;
        }
        try {
            snapshot = jdbc.queryForObject("""
                    SELECT count(*) AS pending,
                        COALESCE(GREATEST(0,EXTRACT(EPOCH FROM(CURRENT_TIMESTAMP-MIN(occurred_at)))),0) AS age
                    FROM proposal_outbox WHERE published_at IS NULL
                    """, (row, index) -> new Snapshot(row.getDouble("pending"), row.getDouble("age"), 1));
        } catch (RuntimeException failure) {
            snapshot = new Snapshot(Double.NaN, Double.NaN, 0);
            LOG.atWarn().addKeyValue("failureType", failure.getClass().getSimpleName())
                    .log("Outbox telemetry snapshot unavailable");
        }
    }

    private record Snapshot(double pending, double ageSeconds, double available) {
    }
}
