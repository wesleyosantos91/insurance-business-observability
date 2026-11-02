package io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging;

import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

public class ProposalConsumerErrorHandler extends CommonContainerStoppingErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalConsumerErrorHandler.class);

    @Override
    public void handleRemaining(Exception failure, List<ConsumerRecord<?, ?>> records,
                                Consumer<?, ?> consumer, MessageListenerContainer container) {
        var log = LOG.atError()
                .addKeyValue("failureType", failure.getClass().getSimpleName());
        if (!records.isEmpty()) {
            var record = records.getFirst();
            log.addKeyValue("topic", record.topic()).addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset());
        }
        log.log("Proposal consumer stopped; failed record not acknowledged; operator action required");
        super.handleRemaining(failure, records, consumer, container);
    }
}
