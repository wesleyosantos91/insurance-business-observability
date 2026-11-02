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
        var handler = new ProposalConsumerErrorHandler();
        assertTrue(handler.seeksAfterHandling());
        var thrown = assertThrows(KafkaException.class,
                () -> handler.handleRemaining(failure, records,
                        mock(Consumer.class), container));
        assertSame(failure, thrown.getCause());
        verify(container, timeout(2000)).stopAbnormally(any(Runnable.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logsDiagnosticContextAndDelegatesTheOriginalFailure(boolean withRecord) {
        var delegate = mock(org.springframework.kafka.listener.CommonErrorHandler.class);
        var handler = new ProposalConsumerErrorHandler(delegate);
        var container = mock(MessageListenerContainer.class);
        var consumer = mock(Consumer.class);
        var failure = new IllegalStateException("invalid event");
        List<ConsumerRecord<?, ?>> records = withRecord
                ? List.of(new ConsumerRecord<>("topic", 1, 7, "key", "invalid")) : List.of();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProposalConsumerErrorHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleRemaining(failure, records, consumer, container);
            verify(delegate).handleRemaining(same(failure), same(records), same(consumer), same(container));
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals(ch.qos.logback.classic.Level.ERROR, event.getLevel());
            assertEquals("Proposal consumer stopped; failed record not acknowledged; operator action required",
                    event.getFormattedMessage());
            var fields = event.getKeyValuePairs().stream().collect(
                    java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertEquals("IllegalStateException", fields.get("failureType"));
            if (withRecord) {
                assertEquals("topic", fields.get("topic"));
                assertEquals(1, fields.get("partition"));
                assertEquals(7L, fields.get("offset"));
            } else {
                assertFalse(fields.containsKey("topic"));
                assertFalse(fields.containsKey("partition"));
                assertFalse(fields.containsKey("offset"));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
