package io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging;

import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.MessageListenerContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalConsumerErrorHandlerTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedRecordsStopContainerInsteadOfBeingSilentlySkipped(boolean withRecord) {
        var container = mock(MessageListenerContainer.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(container).stopAbnormally(any(Runnable.class));
        var failure = new IllegalStateException("invalid event");
        List<ConsumerRecord<?, ?>> records = withRecord
                ? List.of(new ConsumerRecord<>("topic", 1, 7, "key", "invalid")) : List.of();
        var thrown = assertThrows(KafkaException.class,
                () -> new ProposalConsumerErrorHandler().handleRemaining(failure, records,
                        mock(Consumer.class), container));
        assertSame(failure, thrown.getCause());
        verify(container, timeout(2000)).stopAbnormally(any(Runnable.class));
    }
}
