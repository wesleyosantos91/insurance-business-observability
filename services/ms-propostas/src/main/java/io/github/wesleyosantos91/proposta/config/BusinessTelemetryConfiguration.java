package io.github.wesleyosantos91.proposta.config;

import java.util.Set;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BusinessTelemetryConfiguration {
    @Bean
    MeterFilter businessDurations() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return switch (id.getName()) {
                    case "insurance.proposal.register" -> canonical(id, "insurance.business.step.duration", "proposal_register");
                    case "insurance.outbox.publish" -> canonical(id, "insurance.outbox.publish.duration", "outbox_publish");
                    default -> id;
                };
            }
        };
    }

    private Meter.Id canonical(Meter.Id id, String name, String step) {
        var tags = id.getTags().stream()
                .filter(tag -> !Set.of("error", "eventType").contains(tag.getKey())).toList();
        return id.withName(name).replaceTags(Tags.of(tags).and("step", step));
    }
}
