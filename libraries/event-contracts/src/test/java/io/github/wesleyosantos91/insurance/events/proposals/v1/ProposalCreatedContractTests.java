package io.github.wesleyosantos91.insurance.events.proposals.v1;

import java.io.ByteArrayOutputStream;
import org.apache.avro.AvroMissingFieldException;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalCreatedContractTests {

    @Test
    void generatesTheThreeSpecificRecordsInTheOfficialNamespace() {
        assertTrue(SpecificRecord.class.isAssignableFrom(ProposalCreated.class));
        assertTrue(SpecificRecord.class.isAssignableFrom(EventMetadata.class));
        assertTrue(SpecificRecord.class.isAssignableFrom(ProposalCreatedPayload.class));
        assertEquals("io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated",
                ProposalCreated.getClassSchema().getFullName());
        assertNull(EventMetadata.newBuilder().setEventId("event").setEventType("ProposalCreated")
                .setSchemaVersion("1.0.0").setOccurredAt("2026-11-01T12:00:00Z")
                .setProductKey("AUTO").setJourneyType("PROPOSAL").setJourneyId("journey")
                .setRootJourneyId("journey").setCorrelationId("correlation").build().getCausationId());
    }

    @Test
    void binaryAvroRoundTripPreservesMetadataAndPayload() throws Exception {
        var metadata = EventMetadata.newBuilder().setEventId("event").setEventType("ProposalCreated")
                .setSchemaVersion("1.0.0").setOccurredAt("2026-11-01T12:00:00Z")
                .setProductKey("AUTO").setJourneyType("PROPOSAL").setJourneyId("journey")
                .setRootJourneyId("root").setCorrelationId("correlation")
                .setParentJourneyId("parent").setCausationId("cause").build();
        var original = new ProposalCreated(metadata, new ProposalCreatedPayload("proposal"));
        var output = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(output, null);
        new SpecificDatumWriter<>(ProposalCreated.class).write(original, encoder);
        encoder.flush();
        var restored = new SpecificDatumReader<>(ProposalCreated.class)
                .read(null, DecoderFactory.get().binaryDecoder(output.toByteArray(), null));
        assertEquals(original, restored);
        assertEquals("proposal", restored.getPayload().getProposalId().toString());
        assertEquals("correlation", restored.getMetadata().getCorrelationId().toString());
        assertNotEquals('{', output.toByteArray()[0]);
    }

    @Test
    void requiredFieldsCannotBeOmittedByTheBuilder() {
        assertThrows(AvroMissingFieldException.class, () -> ProposalCreatedPayload.newBuilder().build());
    }
}
