package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.nio.ByteBuffer;
import java.util.Map;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.github.wesleyosantos91.insurance.events.proposals.v1.EventMetadata;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreatedPayload;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import static org.junit.jupiter.api.Assertions.*;

class ProposalAvroSerializationTests {
    private static final String TOPIC = "insurance.proposals.events.v1";
    private static final String SUBJECT = TOPIC + "-" + ProposalCreated.getClassSchema().getFullName();
    private final MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
    private final Map<String, Object> configuration = Map.of(
            "schema.registry.url", "http://unused", "auto.register.schemas", false,
            "value.subject.name.strategy", "io.confluent.kafka.serializers.subject.TopicRecordNameStrategy",
            "specific.avro.reader", true);

    @Test
    void usesTheConfluentWireFormatAndDeserializesASpecificRecord() throws Exception {
        int id = registry.register(SUBJECT, new AvroSchema(ProposalCreated.getClassSchema()));
        try (var serializer = new KafkaAvroSerializer(registry);
             var deserializer = new KafkaAvroDeserializer(registry)) {
            serializer.configure(configuration, false);
            deserializer.configure(configuration, false);
            var original = event();
            var bytes = serializer.serialize(TOPIC, original);
            assertEquals(0, bytes[0]);
            assertEquals(id, ByteBuffer.wrap(bytes, 1, 4).getInt());
            assertEquals(original, assertInstanceOf(ProposalCreated.class,
                    deserializer.deserialize(TOPIC, bytes)));
            assertEquals(1, registry.getAllVersions(SUBJECT).size());
        }
    }

    @Test
    void missingSubjectFailsWithoutAutoRegistration() throws Exception {
        try (var serializer = new KafkaAvroSerializer(registry)) {
            serializer.configure(configuration, false);
            assertThrows(SerializationException.class, () -> serializer.serialize(TOPIC, event()));
            var missing = assertThrows(io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException.class,
                    () -> registry.getAllVersions(SUBJECT));
            assertEquals(40401, missing.getErrorCode());
        }
    }

    @Test
    void corruptAvroIsCapturedInTheErrorHandlingDeserializerHeader() {
        try (var deserializer = new ErrorHandlingDeserializer<Object>(new KafkaAvroDeserializer(registry))) {
            deserializer.configure(configuration, false);
            var headers = new RecordHeaders();
            assertNull(deserializer.deserialize(TOPIC, headers, new byte[]{1, 2, 3}));
            assertNotNull(headers.lastHeader("springDeserializerExceptionValue"));
        }
    }

    private ProposalCreated event() {
        return new ProposalCreated(new EventMetadata("event", "ProposalCreated", "1.0.0",
                "2026-11-01T12:00:00Z", "AUTO", "PROPOSAL", "journey", "journey",
                "correlation", null, null), new ProposalCreatedPayload("proposal"));
    }
}
