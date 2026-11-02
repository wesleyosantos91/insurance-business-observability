"""Local infrastructure checks; no application producers or consumers are installed."""
import argparse
import copy
import io
import json
import os
from pathlib import Path
import struct
import sys
import time
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import uuid4

import avro.io
import avro.schema
from confluent_kafka import Consumer, KafkaError, Producer, TopicPartition
from confluent_kafka.admin import AdminClient, ConfigResource, NewTopic

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_FILE = ROOT / "schemas/proposals/proposal-created-v1.avsc"
TOPICS = tuple(f"insurance.{domain}.events.v1" for domain in
               ("proposals", "contracts", "endorsements", "payments"))
TOPIC = TOPICS[0]
BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
REGISTRY = os.getenv("SCHEMA_REGISTRY_URL", "http://schema-registry:8081").rstrip("/")
UI = os.getenv("KAFKA_UI_URL", "http://kafka-ui:8080").rstrip("/")
PARTITIONS = int(os.getenv("TOPIC_PARTITIONS", "3"))
RETENTION = os.getenv("TOPIC_RETENTION_MS", "604800000")
POLICY = "BACKWARD_TRANSITIVE"


class RegistryError(RuntimeError):
    def __init__(self, status, body):
        self.status = status
        super().__init__(f"Registry HTTP {status}: {body}")


def http(base, path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(base + path, data=data, method=method,
                      headers={"Content-Type": "application/vnd.schemaregistry.v1+json",
                               "Accept": "application/json"})
    try:
        with urlopen(request, timeout=15) as response:
            content = response.read()
            return json.loads(content) if content else None
    except HTTPError as error:
        raise RegistryError(error.code, error.read().decode("utf-8")) from error


def api(path, method="GET", body=None):
    return http(REGISTRY, path, method, body)


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load_schema(path=SCHEMA_FILE):
    with Path(path).open(encoding="utf-8") as source:
        schema = json.load(source, object_pairs_hook=unique_keys)
    parsed = avro.schema.parse(json.dumps(schema))
    expected = "io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated"
    if parsed.fullname != expected:
        raise ValueError(f"Unexpected record name: {parsed.fullname}")
    print(f"VALID JSON/Avro: {path} ({parsed.fullname})")
    return schema


def fullname(schema):
    return avro.schema.parse(json.dumps(schema)).fullname


def subject_name(schema):
    # TopicRecordNameStrategy; never use the implicit <topic>-value subject.
    return TOPIC + "-" + fullname(schema)


def subject_path(subject):
    return "/subjects/" + quote(subject, safe="")


def schema_body(schema):
    avro.schema.parse(json.dumps(schema))
    return {"schemaType": "AVRO", "schema": json.dumps(schema)}


def versions(subject):
    try:
        return api(subject_path(subject) + "/versions")
    except RegistryError as error:
        if error.status == 404:
            return []
        raise


def set_policy(subject):
    api("/config/" + quote(subject, safe=""), "PUT", {"compatibility": POLICY})
    actual = api("/config/" + quote(subject, safe=""))["compatibilityLevel"]
    if actual != POLICY:
        raise RuntimeError(f"Wrong compatibility policy: {actual}")


def compatibility(subject, schema):
    if not versions(subject):
        return {"is_compatible": True, "messages": ["First version; no earlier schema."]}
    # This endpoint checks the subject history under the configured transitive policy.
    result = api("/compatibility/subjects/" + quote(subject, safe="") +
                 "/versions?verbose=true", "POST", schema_body(schema))
    # Keep field paths, types, policy and historical version, without dumping whole schemas.
    if "messages" in result:
        result["messages"] = [message for message in result["messages"]
                              if not message.startswith("{oldSchema:")]
    return result


def register(schema, subject=None):
    subject = subject or subject_name(schema)
    set_policy(subject)
    result = compatibility(subject, schema)
    if not result["is_compatible"]:
        raise RuntimeError(f"INCOMPATIBLE {subject}: {json.dumps(result)}")
    previous = versions(subject)
    registered = api(subject_path(subject) + "/versions?normalize=true", "POST", schema_body(schema))
    lookup = api(subject_path(subject) + "?normalize=true", "POST", schema_body(schema))
    current = versions(subject)
    status = "UNCHANGED" if current == previous else "REGISTERED"
    print(f"{status} {subject}: version={lookup['version']} id={registered['id']} policy={POLICY}")
    return lookup


def register_main(path):
    schema = load_schema(path)
    api("/config", "PUT", {"compatibility": POLICY})
    if api("/config")["compatibilityLevel"] != POLICY:
        raise RuntimeError("Global compatibility was not applied.")
    register(schema)


def admin():
    return AdminClient({"bootstrap.servers": BOOTSTRAP, "client.id": "insurance-infra"})


def check_topics(client=None):
    client = client or admin()
    metadata = client.list_topics(timeout=20)
    if len(metadata.brokers) != 1:
        raise RuntimeError("This environment expects exactly one broker.")
    resources = []
    for topic in TOPICS:
        info = metadata.topics.get(topic)
        if info is None or info.error:
            raise RuntimeError(f"Missing/unavailable topic: {topic}")
        if len(info.partitions) != PARTITIONS:
            raise RuntimeError(f"Partition drift: {topic}; expected {PARTITIONS}.")
        if any(len(p.replicas) != 1 or p.leader < 0 for p in info.partitions.values()):
            raise RuntimeError(f"Replication/leader error: {topic}")
        resources.append(ConfigResource(ConfigResource.Type.TOPIC, topic))
    configs = client.describe_configs(resources, request_timeout=20)
    for resource, future in configs.items():
        values = future.result(timeout=25)
        if values["retention.ms"].value != RETENTION or values["cleanup.policy"].value != "delete":
            raise RuntimeError(f"Config drift: {resource.name}; expected retention={RETENTION}, cleanup=delete.")
        print(f"TOPIC OK {resource.name}: partitions={PARTITIONS} replication=1 retention.ms={RETENTION} cleanup=delete")


def create_topics():
    client = admin()
    requests = [NewTopic(topic, num_partitions=PARTITIONS, replication_factor=1,
                         config={"retention.ms": RETENTION, "cleanup.policy": "delete"})
                for topic in TOPICS]
    for topic, future in client.create_topics(requests, request_timeout=20).items():
        try:
            future.result(timeout=25)
            print(f"CREATED {topic}")
        except Exception as error:
            if not error.args or not hasattr(error.args[0], "code") or error.args[0].code() != KafkaError.TOPIC_ALREADY_EXISTS:
                raise
            print(f"EXISTS {topic}")
    check_topics(client)


def payload_fields(schema):
    return next(field for field in schema["fields"] if field["name"] == "payload")["type"]["fields"]


def evolution_tests(schema):
    # Only subjects created here are deleted. The main catalog is never modified.
    subject = "compatibility-check-" + uuid4().hex
    try:
        register(schema, subject)
        evolved = copy.deepcopy(schema)
        payload_fields(evolved).append({"name": "channel", "type": ["null", "string"], "default": None})
        if not compatibility(subject, evolved)["is_compatible"]:
            raise RuntimeError("Expected compatible optional channel with default null.")
        register(evolved, subject)
        old_writer = avro.schema.parse(json.dumps(schema))
        new_reader = avro.schema.parse(json.dumps(evolved))
        old_bytes = io.BytesIO()
        avro.io.DatumWriter(old_writer).write(sample_event(), avro.io.BinaryEncoder(old_bytes))
        old_bytes.seek(0)
        read_back = avro.io.DatumReader(old_writer, new_reader).read(avro.io.BinaryDecoder(old_bytes))
        if read_back["payload"]["channel"] is not None:
            raise RuntimeError("New reader did not apply the null default to an old record.")
        print("PASS compatible evolution: optional channel accepted; new reader decoded v1 with channel=null.")
        incompatible = copy.deepcopy(evolved)
        next(field for field in payload_fields(incompatible) if field["name"] == "proposalId")["type"] = "long"
        rejection = compatibility(subject, incompatible)
        if rejection["is_compatible"]:
            raise RuntimeError("Registry accepted string -> long unexpectedly.")
        try:
            api(subject_path(subject) + "/versions", "POST", schema_body(incompatible))
        except RegistryError as error:
            if error.status != 409:
                raise
            print(f"PASS incompatible evolution: proposalId string -> long rejected (HTTP 409): {json.dumps(rejection)}")
        else:
            raise RuntimeError("Incompatible registration was not rejected.")
        # A reader without channel's default can read v2, but cannot read v1.
        transitive = copy.deepcopy(evolved)
        del next(field for field in payload_fields(transitive) if field["name"] == "channel")["default"]
        only_v2 = api("/compatibility/subjects/" + quote(subject, safe="") +
                      "/versions/2", "POST", schema_body(transitive))
        all_versions = compatibility(subject, transitive)
        if not only_v2["is_compatible"] or all_versions["is_compatible"]:
            raise RuntimeError(f"Transitive check did not distinguish v1 from v2: {only_v2}, {all_versions}")
        print("PASS BACKWARD_TRANSITIVE: candidate compatible with v2 but not v1 rejected.")
        if versions(subject) != [1, 2]:
            raise RuntimeError("An incompatible schema version was registered.")
    finally:
        if versions(subject):
            api(subject_path(subject), "DELETE")
            api(subject_path(subject) + "?permanent=true", "DELETE")
        try:
            api("/config/" + quote(subject, safe=""), "DELETE")
        except RegistryError as error:
            if error.status != 404:
                raise
        print(f"Temporary subject and config removed: {subject}")


def validate_schemas(path):
    schema = load_schema(path)
    subject = subject_name(schema)
    if versions(subject):
        policy = api("/config/" + quote(subject, safe=""))["compatibilityLevel"]
        if policy != POLICY:
            raise RuntimeError(f"Wrong policy on main subject: {policy}")
        result = compatibility(subject, schema)
        if not result["is_compatible"]:
            raise RuntimeError(f"Declarative schema incompatible: {result}")
    evolution_tests(schema)


def sample_event():
    proposal_id = str(uuid4())
    event_id = str(uuid4())
    return {
        "metadata": {
            "eventId": event_id, "eventType": "ProposalCreated", "schemaVersion": "1.0.0",
            "occurredAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "productKey": "AUTO", "journeyType": "PROPOSAL", "journeyId": proposal_id,
            "rootJourneyId": proposal_id, "correlationId": str(uuid4()),
            "parentJourneyId": None, "causationId": None,
        },
        "payload": {"proposalId": proposal_id},
    }


def smoke_test():
    schema = load_schema()
    check_topics()
    subject = subject_name(schema)
    registered = api(subject_path(subject) + "/versions/latest")
    if json.loads(registered["schema"]) != schema:
        raise RuntimeError("Main registry schema differs from the declarative file.")
    if api("/config/" + quote(subject, safe=""))["compatibilityLevel"] != POLICY:
        raise RuntimeError("Main subject compatibility is not BACKWARD_TRANSITIVE.")
    brokers = http(UI, "/api/clusters/insurance-local/brokers")
    if not isinstance(brokers, list) or len(brokers) != 1 or brokers[0]["host"] != "kafka" or brokers[0]["port"] != 29092:
        raise RuntimeError(f"Kafka UI did not discover the internal broker: {brokers}")
    catalog = http(UI, "/api/clusters/insurance-local/schemas")
    if not any(item["subject"] == subject for item in catalog.get("schemas", [])):
        raise RuntimeError("Kafka UI did not query the registered subject from Schema Registry.")
    print("Kafka UI broker/schema APIs OK; Schema Registry API OK.")
    record = sample_event()
    writer_schema = avro.schema.parse(json.dumps(schema))
    avro.io.validate(writer_schema, record, raise_on_error=True)
    buffer = io.BytesIO()
    # Confluent Avro wire format: magic byte 0, 4-byte big-endian schema ID, Avro binary datum.
    buffer.write(struct.pack(">BI", 0, registered["id"]))
    avro.io.DatumWriter(writer_schema).write(record, avro.io.BinaryEncoder(buffer))
    wire = buffer.getvalue()
    delivery = []
    producer = Producer({"bootstrap.servers": BOOTSTRAP, "client.id": "insurance-avro-smoke",
                         "enable.idempotence": True, "acks": "all", "message.timeout.ms": 15000,
                         "allow.auto.create.topics": False})
    producer.produce(TOPIC, key=record["payload"]["proposalId"].encode(), value=wire,
                     on_delivery=lambda error, message: delivery.append((error, message)))
    if producer.flush(20) or not delivery or delivery[0][0]:
        raise RuntimeError(f"Avro delivery failed: {delivery}")
    sent = delivery[0][1]
    consumer = Consumer({"bootstrap.servers": BOOTSTRAP, "group.id": "insurance-smoke-" + uuid4().hex,
                         "enable.auto.commit": False, "allow.auto.create.topics": False})
    try:
        consumer.assign([TopicPartition(TOPIC, sent.partition(), sent.offset())])
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            message = consumer.poll(1)
            if message is None:
                continue
            if message.error():
                raise RuntimeError(str(message.error()))
            if message.offset() != sent.offset():
                raise RuntimeError("Unexpected offset while reading the exact produced record.")
            received = message.value()
            if len(received) < 5 or received[0] != 0:
                raise RuntimeError("Record is not Confluent-framed Avro.")
            schema_id = struct.unpack(">I", received[1:5])[0]
            if schema_id != registered["id"]:
                raise RuntimeError("Wrong schema ID in wire header.")
            fetched = api(f"/schemas/ids/{schema_id}")["schema"]
            decoded = avro.io.DatumReader(avro.schema.parse(fetched), writer_schema).read(
                avro.io.BinaryDecoder(io.BytesIO(received[5:])))
            if decoded != record or message.key().decode() != decoded["payload"]["proposalId"]:
                raise RuntimeError("Decoded fields or proposalId message key differ.")
            print("PASS AVRO ROUNDTRIP " + json.dumps({"topic": TOPIC, "partition": message.partition(),
                  "offset": message.offset(), "schemaId": schema_id, "subject": subject,
                  "key": message.key().decode(), "event": decoded}, ensure_ascii=False))
            return
        raise RuntimeError("Timed out waiting for the produced Avro record.")
    finally:
        consumer.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("topics", "register", "validate", "smoke"))
    parser.add_argument("--schema", type=Path, default=SCHEMA_FILE)
    args = parser.parse_args()
    if args.command == "topics":
        create_topics()
    elif args.command == "register":
        register_main(args.schema)
    elif args.command == "validate":
        validate_schemas(args.schema)
    else:
        smoke_test()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
