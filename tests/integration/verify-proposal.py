"""Inspect real Kafka bytes independently of the Java serializer (runs in kafka-tools)."""
import argparse
import io
import json
import os
import struct
import time
import urllib.parse
import urllib.request
import uuid

import avro.io
import avro.schema
from confluent_kafka import Consumer, KafkaError, Producer

TOPIC = "insurance.proposals.events.v1"
FULL_NAME = "io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated"
SUBJECT = f"{TOPIC}-{FULL_NAME}"


def get_json(registry, path):
    with urllib.request.urlopen(registry + path, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    for name in ("proposal-id", "journey-id", "correlation-id"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--trace-id", help="Expected original W3C trace ID for this synthetic journey")
    parser.add_argument("--redeliver", action="store_true", help="Redeliver only this validated synthetic event to verify Inbox deduplication")
    args = parser.parse_args()
    registry = os.environ["SCHEMA_REGISTRY_URL"].rstrip("/")
    encoded_subject = urllib.parse.quote(SUBJECT, safe="")
    versions = get_json(registry, f"/subjects/{encoded_subject}/versions")
    latest = get_json(registry, f"/subjects/{encoded_subject}/versions/latest")
    assert get_json(registry, f"/config/{encoded_subject}")["compatibilityLevel"] == "BACKWARD_TRANSITIVE"
    consumer = Consumer({
        "bootstrap.servers": os.environ["KAFKA_BOOTSTRAP_SERVERS"],
        "group.id": "proposal-flow-inspector-" + str(uuid.uuid4()),
        "enable.auto.commit": False,
        "auto.offset.reset": "earliest",
    })
    consumer.subscribe([TOPIC])
    deadline = time.monotonic() + 45
    try:
        while time.monotonic() < deadline:
            record = consumer.poll(1)
            if record is None:
                continue
            if record.error():
                if record.error().code() == KafkaError._PARTITION_EOF:
                    continue
                raise RuntimeError(str(record.error()))
            if record.key() is None or record.key().decode() != args.proposal_id:
                continue
            wire = record.value()
            assert len(wire) > 5 and wire[0] == 0, "Missing Confluent Avro wire header"
            schema_id = struct.unpack(">I", wire[1:5])[0]
            writer_schema = avro.schema.parse(get_json(registry, f"/schemas/ids/{schema_id}")["schema"])
            event = avro.io.DatumReader(writer_schema).read(avro.io.BinaryDecoder(io.BytesIO(wire[5:])))
            metadata = event["metadata"]
            assert schema_id == latest["id"]
            assert writer_schema.fullname == FULL_NAME
            assert event["payload"]["proposalId"] == args.proposal_id
            assert metadata["journeyId"] == args.journey_id
            assert metadata["rootJourneyId"] == args.journey_id
            assert metadata["correlationId"] == args.correlation_id
            assert metadata["eventType"] == "ProposalCreated"
            assert metadata["journeyType"] == "PROPOSAL"
            assert metadata["schemaVersion"] == "1.0.0"
            assert metadata["productKey"] == "AUTO"
            assert metadata["occurredAt"].endswith("Z")
            uuid.UUID(metadata["eventId"])
            assert metadata["parentJourneyId"] is None
            assert metadata["causationId"] is None
            assert versions == get_json(registry, f"/subjects/{encoded_subject}/versions")
            traceparent = dict(record.headers() or []).get("traceparent")
            traceparent = traceparent.decode() if traceparent else None
            if args.trace_id:
                assert traceparent and traceparent.split("-")[1] == args.trace_id, "W3C trace context changed"
            redelivery = None
            if args.redeliver:
                producer = Producer({"bootstrap.servers": os.environ["KAFKA_BOOTSTRAP_SERVERS"],
                                     "acks": "all", "enable.idempotence": True})
                acknowledgments = []
                def confirmed(error, message):
                    if error:
                        raise RuntimeError(str(error))
                    acknowledgments.append({"partition": message.partition(), "offset": message.offset()})
                producer.produce(TOPIC, key=record.key(), value=wire, partition=record.partition(), headers=record.headers(), on_delivery=confirmed)
                assert producer.flush(15) == 0 and len(acknowledgments) == 1
                redelivery = acknowledgments[0]
            print(json.dumps({
                "result": "AVRO_CONFIRMED", "topic": TOPIC,
                "partition": record.partition(), "offset": record.offset(),
                "key": record.key().decode(), "schemaId": schema_id,
                "registryVersion": latest["version"], "subject": SUBJECT,
                "compatibility": "BACKWARD_TRANSITIVE", **metadata,
                "proposalId": args.proposal_id, "traceparent": traceparent, "redelivery": redelivery,
            }))
            return
        raise TimeoutError("Proposal was not found in real Kafka within 45 seconds")
    finally:
        consumer.close()


if __name__ == "__main__":
    main()
