package io.github.wesleyosantos91.proposta.bdd;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalOutboxStore;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalStore;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Quando;
import io.cucumber.java.pt.Então;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

public class IdempotencySteps {
    private static final String TOPIC = "insurance.proposals.events.v1";
    private static final String SUBJECT = TOPIC + "-" + ProposalCreated.getClassSchema().getFullName();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<AutoCloseable> RESOURCES = new ArrayList<>();
    private static Network network;
    private static OtlpCapture telemetry;
    private static ConfluentKafkaContainer kafka;
    private static PostgreSQLContainer propostasDb;
    private static PostgreSQLContainer cadastroDb;
    private static GenericContainer<?> registry;
    private static GenericContainer<?> producer;
    private static GenericContainer<?> replica;
    private static GenericContainer<?> cadastro;
    private static int schemaId;
    private String key;
    private String expectedTraceId;
    private String relayProposal;
    private String relayEvent;
    private List<String> relayBatch;
    private final List<HttpResponse<String>> responses = new ArrayList<>();
    private JsonNode beforeTimeout;
    private long proposalCountBefore;
    private List<ConsumerRecord<String, byte[]>> duplicates;

    @BeforeAll
    public static void startInfrastructure() throws Exception {
        try {
            telemetry = new OtlpCapture();
            RESOURCES.add(telemetry);
            Testcontainers.exposeHostPorts(telemetry.port());
            network = Network.newNetwork();
            RESOURCES.add(network);
            kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:8.1.1")
                    .withNetwork(network).withNetworkAliases("kafka").withListener("kafka:19092")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
            RESOURCES.add(kafka);
            kafka.start();
            propostasDb = database("propostas", "propostas-db");
            cadastroDb = database("cadastro", "cadastro-db");
            registry = new GenericContainer<>("confluentinc/cp-schema-registry:8.1.1")
                    .withNetwork(network).withNetworkAliases("registry")
                    .withExposedPorts(8081)
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "registry")
                    .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("SCHEMA_REGISTRY_HEAP_OPTS", "-Xms128m -Xmx256m")
                    .waitingFor(Wait.forHttp("/subjects")).withStartupTimeout(Duration.ofMinutes(2));
            RESOURCES.add(registry);
            registry.start();
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
            }
            registryRequest("/config", "PUT", "{\"compatibility\":\"BACKWARD_TRANSITIVE\"}");
            registryRequest("/config/" + SUBJECT, "PUT", "{\"compatibility\":\"BACKWARD_TRANSITIVE\"}");
            var root = Path.of(System.getProperty("insurance.repository.root")).toAbsolutePath();
            var schema = Files.readString(root.resolve("infrastructure/kafka/schemas/proposals/proposal-created-v1.avsc"));
            schemaId = JSON.readTree(registryRequest("/subjects/" + SUBJECT + "/versions", "POST",
                    JSON.createObjectNode().put("schemaType", "AVRO").put("schema", schema).toString())).path("id").asInt();
            cadastro = application("ms-cadastro", 8082, "cadastro-db", "cadastro");
            producer = application("ms-propostas", 8081, "propostas-db", "propostas");
            replica = application("ms-propostas", 8081, "propostas-db", "propostas");
            installOutboxAudit();
        } catch (Exception failure) {
            stopInfrastructure();
            throw failure;
        }
    }

    private static PostgreSQLContainer database(String name, String alias) {
        var container = new PostgreSQLContainer("postgres:17.11").withDatabaseName(name)
                .withUsername("insurance").withPassword("insurance").withNetwork(network).withNetworkAliases(alias);
        RESOURCES.add(container);
        container.start();
        return container;
    }

    private static GenericContainer<?> application(String service, int port, String databaseHost, String database) {
        var container = new GenericContainer<>("insurance/" + service + ":idempotency-aot")
                .withNetwork(network).withExposedPorts(port)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
                .withEnv("SCHEMA_REGISTRY_URL", "http://registry:8081")
                .withEnv("DATABASE_URL", "jdbc:postgresql://" + databaseHost + ":5432/" + database
                        + "?connectTimeout=3&socketTimeout=10&ApplicationName=" + service + "-" + UUID.randomUUID())
                .withEnv("DATABASE_USERNAME", "insurance").withEnv("DATABASE_PASSWORD", "insurance")
                .withEnv("JAVA_TOOL_OPTIONS", "-Xmx256m -Xlog:aot=info")
                .withEnv("OUTBOX_LEASE_DURATION", "15s")
                .withEnv("DD_AGENT_HOST", "127.0.0.1")
                .withEnv("DD_ENV", "test").withEnv("DD_VERSION", "test")
                .withEnv("OTEL_SERVICE_NAME", service)
                .withEnv("OTEL_RESOURCE_ATTRIBUTES", "service.name=" + service
                        + ",service.version=test,deployment.environment.name=test,deployment.environment=test")
                .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://host.testcontainers.internal:" + telemetry.port())
                .withEnv("OTLP_METRICS_STEP", "2s")
                .waitingFor(Wait.forHttp("/actuator/health").forPort(port))
                .withStartupTimeout(Duration.ofMinutes(2));
        RESOURCES.add(container);
        container.start();
        return container;
    }

    @AfterAll
    public static void stopInfrastructure() throws Exception {
        var logs = Path.of("target", "cucumber", "container-logs");
        Files.createDirectories(logs);
        for (int index = RESOURCES.size() - 1; index >= 0; index--) {
            var resource = RESOURCES.get(index);
            try {
                if (resource instanceof GenericContainer<?> container && container.isRunning()) {
                    Files.writeString(logs.resolve("container-" + index + ".log"), container.getLogs());
                }
                resource.close();
            } catch (Exception failure) {
                System.err.println("Container cleanup: " + failure.getMessage());
            }
        }
        RESOURCES.clear();
    }

    @Dado("uma chave de idempotência inédita")
    public void uniqueKey() throws Exception {
        key = UUID.randomUUID().toString();
        proposalCountBefore = scalar(propostasDb, "SELECT count(*) FROM proposals");
    }

    @Quando("envio a mesma proposta {int} vez(es)")
    public void sendRepeated(int count) throws Exception {
        for (int index = 0; index < count; index++) {
            responses.add(HTTP.send(request(index % 2 == 0 ? producer : replica, "AUTO", key),
                    HttpResponse.BodyHandlers.ofString()));
        }
    }

    @Quando("envio {int} solicitações concorrentes às duas réplicas")
    public void sendConcurrent(int count) {
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int index = 0; index < count; index++) {
            pending.add(HTTP.sendAsync(request(index % 2 == 0 ? producer : replica, "AUTO", key),
                    HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        pending.forEach(future -> responses.add(future.join()));
    }

    @Então("todas as respostas confirmam os mesmos identificadores")
    public void sameIds() throws Exception {
        assertFalse(responses.isEmpty());
        var first = JSON.readTree(responses.getFirst().body());
        for (var response : responses) {
            assertEquals(202, response.statusCode(), response.body());
            assertEquals(first, JSON.readTree(response.body()));
        }
    }

    @Então("existe uma proposta, uma intenção de publicação e um recebimento")
    public void singleDurableEffect() throws Exception {
        var id = proposalId();
        assertEquals(1, scalar(propostasDb, "SELECT count(*) FROM proposals WHERE proposal_id=?", id));
        assertEquals(1, scalar(propostasDb, "SELECT count(*) FROM proposal_outbox WHERE proposal_id=?", id));
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            assertEquals(1, scalar(cadastroDb, "SELECT count(*) FROM received_proposals WHERE proposal_id=?", id));
            assertEquals(1, scalar(cadastroDb, """
                    SELECT count(*) FROM proposal_inbox i JOIN received_proposals r ON r.event_id=i.event_id
                    WHERE r.proposal_id=?
                    """, id));
        });
    }

    @Então("somente um evento foi publicado para a proposta")
    public void oneKafkaRecord() throws Exception {
        var records = records(proposalId());
        assertEquals(1, records.size());
        var event = decode(records.getFirst());
        var http = JSON.readTree(responses.getFirst().body());
        assertEquals(http.path("proposalId").asText(), event.getPayload().getProposalId().toString());
        assertEquals(http.path("journeyId").asText(), event.getMetadata().getJourneyId().toString());
        assertEquals(http.path("correlationId").asText(), event.getMetadata().getCorrelationId().toString());
    }

    @Quando("reutilizo a chave com outro produto")
    public void conflictingRequest() throws Exception {
        sendRepeated(1);
        responses.add(HTTP.send(request(replica, "HOME", key), HttpResponse.BodyHandlers.ofString()));
    }

    @Então("a segunda solicitação retorna conflito")
    public void conflictResponse() throws Exception {
        assertEquals(202, responses.getFirst().statusCode());
        assertEquals(409, responses.getLast().statusCode());
        assertEquals(409, JSON.readTree(responses.getLast().body()).path("status").asInt());
        assertTrue(responses.getLast().headers().firstValue("Content-Type").orElse("").contains("problem+json"));
    }

    @Quando("reinicio as duas réplicas produtoras")
    public void restartProducers() {
        restart(producer);
        restart(replica);
    }

    @Quando("o Kafka fica indisponível durante a solicitação")
    public void kafkaUnavailable() throws Exception {
        DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            responses.add(HTTP.send(request(producer, "AUTO", key), HttpResponse.BodyHandlers.ofString()));
            try (var connection = connection(propostasDb);
                 var statement = connection.prepareStatement("""
                         SELECT p.proposal_id,p.journey_id,p.correlation_id FROM proposals p
                         JOIN proposal_outbox o ON p.proposal_id=o.proposal_id
                         WHERE o.published_at IS NULL ORDER BY o.occurred_at DESC LIMIT 1
                         """);
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                beforeTimeout = JSON.createObjectNode().put("proposalId", rows.getString(1))
                        .put("journeyId", rows.getString(2)).put("correlationId", rows.getString(3));
            }
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    @Então("recebo timeout e a proposta permanece pendente")
    public void timeoutPending() throws Exception {
        assertEquals(504, responses.getFirst().statusCode(), responses.getFirst().body());
        assertNotNull(beforeTimeout);
        assertEquals(proposalCountBefore + 1, scalar(propostasDb, "SELECT count(*) FROM proposals"));
    }

    @Quando("o Kafka volta e repito a solicitação")
    public void retryAfterRecovery() throws Exception {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertEquals(1, scalar(propostasDb,
                        "SELECT count(*) FROM proposal_outbox WHERE proposal_id=? AND published_at IS NOT NULL",
                        beforeTimeout.path("proposalId").asText())));
        responses.clear();
        sendRepeated(1);
    }

    @Então("recebo os identificadores persistidos antes do timeout")
    public void persistedIdentity() throws Exception {
        assertEquals(202, responses.getFirst().statusCode());
        var actual = JSON.readTree(responses.getFirst().body());
        for (var field : List.of("proposalId", "journeyId", "correlationId")) {
            assertEquals(beforeTimeout.path(field), actual.path(field));
        }
    }

    @Quando("reenvio duas cópias do mesmo evento Avro")
    public void resendEvent() throws Exception {
        var original = records(proposalId()).getFirst();
        var event = decode(original);
        try (var sender = new KafkaProducer<String, ProposalCreated>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer", KafkaAvroSerializer.class.getName(),
                "schema.registry.url", registryUrl(),
                "value.subject.name.strategy", "io.confluent.kafka.serializers.subject.TopicRecordNameStrategy",
                "auto.register.schemas", false, "acks", "all", "enable.idempotence", true))) {
            sender.send(new ProducerRecord<>(TOPIC, proposalId(), event)).get(15, TimeUnit.SECONDS);
            sender.send(new ProducerRecord<>(TOPIC, proposalId(), event)).get(15, TimeUnit.SECONDS);
        }
        duplicates = records(proposalId());
        assertEquals(3, duplicates.size());
    }

    @Então("as cópias são confirmadas sem duplicar o recebimento")
    public void duplicatesAcknowledged() throws Exception {
        singleDurableEffect();
        assertCommitted(duplicates.getLast());
        assertTrue(cadastro.getLogs().contains("Duplicate ProposalCreated already processed"));
    }

    @Quando("o recebimento falha depois de reservar o evento")
    public void failReceipt() throws Exception {
        execute(cadastroDb, "ALTER TABLE received_proposals ADD CONSTRAINT bdd_reject CHECK(product_key <> 'FAIL_ONCE')");
        responses.add(HTTP.send(request(producer, "FAIL_ONCE", key), HttpResponse.BodyHandlers.ofString()));
        assertEquals(202, responses.getFirst().statusCode());
        await().atMost(Duration.ofSeconds(30)).until(() -> cadastro.getLogs()
                .contains("Proposal consumer stopped; failed record not acknowledged"));
    }

    @Então("não existe marca nem recebimento confirmado para o evento")
    public void rolledBack() throws Exception {
        var event = decode(records(proposalId()).getFirst());
        assertEquals(0, scalar(cadastroDb, "SELECT count(*) FROM proposal_inbox WHERE event_id=?",
                event.getMetadata().getEventId().toString()));
        assertEquals(0, scalar(cadastroDb, "SELECT count(*) FROM received_proposals WHERE proposal_id=?", proposalId()));
        var failed = records(proposalId()).getFirst();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets("insurance-cadastro-v1")
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            var committed = offsets.get(new TopicPartition(TOPIC, failed.partition()));
            assertTrue(committed == null || committed.offset() <= failed.offset(),
                    "Offset must not advance past a rolled-back event");
        }
    }

    @Quando("corrijo a falha e reinicio o cadastro")
    public void fixReceipt() throws Exception {
        execute(cadastroDb, "ALTER TABLE received_proposals DROP CONSTRAINT bdd_reject");
        restart(cadastro);
    }

    @Então("o evento é recebido uma única vez")
    public void recoveredReceipt() throws Exception {
        singleDurableEffect();
        assertCommitted(records(proposalId()).getFirst());
    }

    @Quando("envio uma solicitação sem Idempotency-Key")
    public void missingKey() throws Exception {
        responses.add(HTTP.send(request(producer, "AUTO", null), HttpResponse.BodyHandlers.ofString()));
    }

    @Então("recebo erro de validação sem criar uma proposta")
    public void invalidKey() throws Exception {
        assertEquals(400, responses.getFirst().statusCode());
        assertEquals(proposalCountBefore, scalar(propostasDb, "SELECT count(*) FROM proposals"));
    }

    @Quando("crio uma proposta com contexto W3C conhecido")
    public void tracedProposal() throws Exception {
        expectedTraceId = UUID.randomUUID().toString().replace("-", "");
        var original = request(producer, "AUTO", key);
        var traced = HttpRequest.newBuilder(original.uri()).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .header("traceparent", "00-" + expectedTraceId + "-1234567890123456-01")
                .header("tracestate", "insurance=origin")
                .POST(HttpRequest.BodyPublishers.ofString("{\"productKey\":\"AUTO\"}")).build();
        responses.add(HTTP.send(traced, HttpResponse.BodyHandlers.ofString()));
        assertEquals(202, responses.getFirst().statusCode(), responses.getFirst().body());
        singleDurableEffect();
    }

    @Então("registro, relay e cadastro exportam spans do mesmo trace")
    public void traceAcrossTheDurableHandoff() throws Exception {
        var id = proposalId();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (var name : List.of("insurance.proposal.register", "insurance.outbox.publish", "insurance.proposal.consume")) {
                var spans = telemetry.spans()
                        .filter(point -> point.span().getName().equals(name)
                                && OtlpCapture.attribute(point.span().getAttributesList(), "proposalId").equals(id))
                        .toList();
                assertFalse(spans.isEmpty(), "Missing span " + name);
                for (var point : spans) {
                    assertEquals(expectedTraceId, HexFormat.of().formatHex(point.span().getTraceId().toByteArray()));
                    assertEquals(name.endsWith("consume") ? "ms-cadastro" : "ms-propostas", point.service());
                    assertFalse(OtlpCapture.attribute(point.span().getAttributesList(), "eventId").isBlank());
                    assertFalse(OtlpCapture.attribute(point.span().getAttributesList(), "journeyId").isBlank());
                }
            }
        });
    }

    @Então("Kafka e Outbox preservam o contexto W3C")
    public void persistedAndKafkaHeaders() throws Exception {
        try (var db = connection(propostasDb); var sql = db.prepareStatement(
                "SELECT traceparent,tracestate FROM proposal_outbox WHERE proposal_id=?")) {
            sql.setString(1, proposalId());
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(expectedTraceId, rows.getString(1).split("-")[1]);
                assertEquals("insurance=origin", rows.getString(2));
            }
        }
        for (var record : records(proposalId())) {
            assertNotNull(record.headers().lastHeader("traceparent"));
            var parent = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
            assertEquals(expectedTraceId, parent.split("-")[1]);
        }
    }

    @Então("os logs JSON correlacionam o trace e os identificadores de negócio")
    public void structuredLogCorrelation() throws Exception {
        var id = proposalId();
        for (var container : List.of(producer, replica, cadastro)) {
            assertFalse(container.getLogs().contains("failed to append"), "Structured log encoder must not fail");
            if (container != cadastro && !container.getLogs().contains(id)) {
                continue;
            }
            var matching = container.getLogs().lines().filter(line -> line.startsWith("{"))
                    .map(line -> {
                        try { return JSON.readTree(line); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                    })
                    .filter(line -> line.path("proposalId").asText().equals(id)
                            && line.path("trace_id").asText().equals(expectedTraceId))
                    .toList();
            assertFalse(matching.isEmpty(), "Missing correlated JSON log");
            assertTrue(matching.stream().anyMatch(line -> !line.path("journeyId").asText().isBlank()
                    && !line.path("eventId").asText().isBlank()
                    && !line.path("correlationId").asText().isBlank()
                    && !line.path("span_id").asText().isBlank()));
        }
    }

    @Então("as métricas OTLP possuem dimensões limitadas sem IDs de negócio")
    public void boundedMetricDimensions() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (var name : List.of("insurance.proposal.registrations", "insurance.outbox.dispatches",
                    "insurance.outbox.pending", "insurance.proposal.receipts", "insurance.proposal.delivery.latency")) {
                assertTrue(telemetry.metrics().anyMatch(point -> point.metric().getName().equals(name)), name);
            }
        });
        var forbidden = java.util.Set.of("eventid", "proposalid", "journeyid", "rootjourneyid",
                "correlationid", "parentjourneyid", "causationid", "traceid", "spanid", "productkey", "idempotencykey");
        telemetry.metrics().forEach(point -> {
            assertTrue(List.of("ms-propostas", "ms-cadastro").contains(point.service()), point.service());
            var metric = point.metric();
            var attributes = new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>();
            metric.getSum().getDataPointsList().forEach(value -> attributes.addAll(value.getAttributesList()));
            metric.getGauge().getDataPointsList().forEach(value -> attributes.addAll(value.getAttributesList()));
            metric.getHistogram().getDataPointsList().forEach(value -> attributes.addAll(value.getAttributesList()));
            metric.getExponentialHistogram().getDataPointsList()
                    .forEach(value -> attributes.addAll(value.getAttributesList()));
            for (var attribute : attributes) {
                assertFalse(forbidden.contains(attribute.getKey().replace(".", "").replace("_", "").toLowerCase()),
                        "High cardinality metric dimension: " + attribute.getKey());
            }
        });
    }

    @Quando("o destino OTLP fica indisponível durante uma proposta")
    public void telemetryUnavailable() throws Exception {
        telemetry.available = false;
        try {
            responses.add(HTTP.send(request(producer, "AUTO", key), HttpResponse.BodyHandlers.ofString()));
            assertEquals(202, responses.getFirst().statusCode(), responses.getFirst().body());
            singleDurableEffect();
            assertCommitted(records(proposalId()).getFirst());
        } finally {
            telemetry.available = true;
        }
    }

    private static void installOutboxAudit() throws Exception {
        execute(propostasDb, """
                CREATE TABLE bdd_outbox_transitions(
                    event_id VARCHAR(36), kind TEXT, attempt INTEGER, owner TEXT,
                    token UUID, delay_ms DOUBLE PRECISION
                );
                CREATE FUNCTION bdd_audit_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.claim_token IS NOT NULL AND NEW.claim_token IS DISTINCT FROM OLD.claim_token THEN
                        INSERT INTO bdd_outbox_transitions VALUES(
                            NEW.event_id,'claim',NEW.attempts,current_setting('application_name'),NEW.claim_token,NULL);
                    ELSIF OLD.claim_token IS NOT NULL AND NEW.claim_token IS NULL AND NEW.published_at IS NULL THEN
                        INSERT INTO bdd_outbox_transitions VALUES(
                            NEW.event_id,'retry',NEW.attempts,current_setting('application_name'),OLD.claim_token,
                            EXTRACT(EPOCH FROM (NEW.next_attempt_at-CURRENT_TIMESTAMP))*1000);
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER bdd_audit_outbox AFTER UPDATE ON proposal_outbox
                    FOR EACH ROW EXECUTE FUNCTION bdd_audit_outbox();
                """);
    }

    @Quando("o broker falha durante duas tentativas persistidas do relay")
    public void relayRetriesWithoutHoldingDatabaseTransaction() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            var pending = HTTP.sendAsync(request(producer, "OUTBOX_RETRY", key), HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                rememberRelay();
                assertEquals(1, scalar(propostasDb, """
                        SELECT count(*) FROM proposal_outbox WHERE proposal_id=?
                        AND claim_token IS NOT NULL AND claimed_until>CURRENT_TIMESTAMP AND published_at IS NULL
                        """, relayProposal));
            });
            assertEquals(0, scalar(propostasDb, """
                    SELECT count(*) FROM pg_stat_activity
                    WHERE application_name LIKE 'ms-propostas-%' AND state LIKE 'idle in transaction%'
                    """), "Kafka wait must not retain a database transaction");
            try (var db = connection(propostasDb); var sql = db.createStatement()) {
                sql.execute("SET lock_timeout='1s'");
                sql.execute("BEGIN");
                sql.executeQuery("SELECT event_id FROM proposal_outbox WHERE proposal_id='" + relayProposal
                        + "' FOR UPDATE NOWAIT").close();
                sql.execute("ROLLBACK");
            }
            assertEquals(504, pending.get(20, TimeUnit.SECONDS).statusCode());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertTrue(scalar(propostasDb, """
                            SELECT count(*) FROM bdd_outbox_transitions WHERE event_id=? AND kind='retry'
                            """, relayEvent) >= 2));
            assertEquals(0, scalar(propostasDb,
                    "SELECT count(*) FROM proposal_outbox WHERE event_id=? AND published_at IS NOT NULL", relayEvent));
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    @Então("o retry persistido respeita os limites do backoff com jitter")
    public void persistedJitter() throws Exception {
        try (var db = connection(propostasDb); var sql = db.prepareStatement("""
                SELECT attempt,delay_ms FROM bdd_outbox_transitions
                WHERE event_id=? AND kind='retry' ORDER BY attempt
                """)) {
            sql.setString(1, relayEvent);
            try (var rows = sql.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    var ceiling = Math.min(60_000, 1_000L << Math.min(6, rows.getInt(1) - 1));
                    assertTrue(rows.getDouble(2) >= ceiling / 2.0 && rows.getDouble(2) <= ceiling);
                    count++;
                }
                assertTrue(count >= 2);
            }
        }
    }

    @Quando("o Kafka confirma mas a atualização de publicação falha no banco")
    public void kafkaAcknowledgedDatabaseFailed() throws Exception {
        execute(propostasDb, """
                CREATE FUNCTION bdd_reject_publication() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.published_at IS NOT NULL AND EXISTS(
                        SELECT 1 FROM proposals WHERE proposal_id=NEW.proposal_id AND product_key='OUTBOX_COMMIT_FAIL')
                    THEN RAISE EXCEPTION 'BDD: Kafka ACK cannot be committed';
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER bdd_reject_publication BEFORE UPDATE ON proposal_outbox
                    FOR EACH ROW EXECUTE FUNCTION bdd_reject_publication();
                """);
        try {
            var response = HTTP.send(request(producer, "OUTBOX_COMMIT_FAIL", key), HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode(), response.body());
            rememberRelay();
            assertFalse(records(relayProposal).isEmpty(), "Kafka already contains the acknowledged event");
            assertEquals(1, scalar(propostasDb, """
                    SELECT count(*) FROM proposal_outbox WHERE event_id=?
                    AND published_at IS NULL AND claim_token IS NOT NULL
                    """, relayEvent), "Failed completion must retain the durable claim");
            assertTrue(producer.getLogs().contains("Outbox state update failed")
                    || replica.getLogs().contains("Outbox state update failed"));
        } finally {
            execute(propostasDb, """
                    DROP TRIGGER bdd_reject_publication ON proposal_outbox;
                    DROP FUNCTION bdd_reject_publication();
                    """);
        }
    }

    @Então("o lease permite republicar o mesmo evento após a correção")
    public void duplicateAfterDatabaseRecovery() throws Exception {
        waitPublished();
        assertTrue(records(relayProposal).size() >= 2, "ACK/DB failure window must cause real Kafka redelivery");
        assertTrue(scalar(propostasDb,
                "SELECT attempts FROM proposal_outbox WHERE event_id=?", relayEvent) >= 2);
    }

    @Quando("as duas réplicas publicam um lote concorrente de propostas")
    public void concurrentRelays() throws Exception {
        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int index = 0; index < 12; index++) {
            pending.add(HTTP.sendAsync(request(index % 2 == 0 ? producer : replica,
                    "OUTBOX_CONCURRENT", key + "-" + index), HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(25, TimeUnit.SECONDS);
        relayBatch = new ArrayList<>();
        for (var response : pending) {
            var result = response.get();
            assertEquals(202, result.statusCode(), result.body());
            relayBatch.add(JSON.readTree(result.body()).path("proposalId").asText());
        }
    }

    @Então("ambos os relays participam sem reivindicação ou publicação duplicada")
    public void twoOwnersPublishOncePerEvent() throws Exception {
        var owners = new java.util.HashSet<String>();
        for (var id : relayBatch) {
            assertEquals(1, scalar(propostasDb, """
                    SELECT count(*) FROM proposal_outbox
                    WHERE proposal_id=? AND attempts=1 AND published_at IS NOT NULL AND claim_token IS NULL
                    """, id));
            assertEquals(1, records(id).size(), "A live lease must prevent duplicate sends");
            try (var db = connection(propostasDb); var sql = db.prepareStatement("""
                    SELECT a.owner FROM bdd_outbox_transitions a JOIN proposal_outbox o ON o.event_id=a.event_id
                    WHERE o.proposal_id=? AND a.kind='claim'
                    """)) {
                sql.setString(1, id);
                try (var rows = sql.executeQuery()) {
                    assertTrue(rows.next());
                    owners.add(rows.getString(1));
                    assertFalse(rows.next(), "Only one claim for this event");
                }
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(1,
                    scalar(cadastroDb, "SELECT count(*) FROM received_proposals WHERE proposal_id=?", id)));
        }
        assertEquals(2, owners.size(), "Both independent application relays must have claimed work");
    }

    @Quando("encerro os dois produtores com uma reivindicação ativa e Kafka parado")
    public void crashWithLiveClaim() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            var pending = HTTP.sendAsync(request(producer, "OUTBOX_CRASH", key), HttpResponse.BodyHandlers.ofString());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                rememberRelay();
                assertEquals(1, scalar(propostasDb, """
                        SELECT count(*) FROM proposal_outbox WHERE event_id=?
                        AND claim_token IS NOT NULL AND claimed_until>CURRENT_TIMESTAMP
                        """, relayEvent));
            });
            docker.killContainerCmd(producer.getContainerId()).exec();
            docker.killContainerCmd(replica.getContainerId()).exec();
            pending.handle((response, failure) -> null).get(20, TimeUnit.SECONDS);
            assertEquals(1, scalar(propostasDb, """
                    SELECT count(*) FROM proposal_outbox WHERE event_id=?
                    AND published_at IS NULL AND claim_token IS NOT NULL
                    """, relayEvent), "Process death must leave its durable claim recoverable");
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    @Então("o reinício retoma o lease abandonado sem alterar o eventId")
    public void restartWithAbandonedLease() throws Exception {
        restart(producer);
        restart(replica);
        waitPublished();
        assertTrue(scalar(propostasDb,
                "SELECT attempts FROM proposal_outbox WHERE event_id=?", relayEvent) >= 2);
    }

    @Quando("uma reivindicação expira e outro dono assume o registro")
    public void expiredOwnerIsFenced() throws Exception {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(producer.getContainerId()).exec();
        docker.pauseContainerCmd(replica.getContainerId()).exec();
        try {
            var url = propostasDb.getJdbcUrl();
            var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                    + "ApplicationName=outbox-fencing-test", propostasDb.getUsername(), propostasDb.getPassword());
            var jdbc = new JdbcTemplate(source);
            var manager = new DataSourceTransactionManager(source);
            var proposal = new ProposalStore(jdbc, manager, new ProposalTelemetry(ObservationRegistry.NOOP,
                    new SimpleMeterRegistry(), Tracer.NOOP, Propagator.NOOP)).register(key, "OUTBOX_FENCING", Instant.now());
            relayProposal = proposal.proposalId();
            relayEvent = proposal.eventId();
            var firstOwner = new ProposalOutboxStore(jdbc, manager);
            var secondOwner = new ProposalOutboxStore(jdbc, manager);
            var first = firstOwner.claim(Duration.ofSeconds(30)).orElseThrow();
            assertEquals(relayEvent, first.proposal().eventId());
            assertTrue(secondOwner.claim(Duration.ofSeconds(30)).isEmpty(),
                    "A live claim cannot be acquired by a second owner");
            jdbc.update("UPDATE proposal_outbox SET claimed_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE event_id=?",
                    relayEvent);
            var second = secondOwner.claim(Duration.ofSeconds(1)).orElseThrow();
            assertEquals(relayEvent, second.proposal().eventId());
            assertNotEquals(first.token(), second.token());
            assertEquals(2, second.attempts());
            assertFalse(firstOwner.published(first));
            assertFalse(firstOwner.retry(first, Duration.ofSeconds(1), "stale"));
            assertEquals(second.token(), jdbc.queryForObject(
                    "SELECT claim_token FROM proposal_outbox WHERE event_id=?", UUID.class, relayEvent));
            assertEquals(0, scalar(propostasDb,
                    "SELECT count(*) FROM proposal_outbox WHERE event_id=? AND published_at IS NOT NULL", relayEvent));
        } finally {
            docker.unpauseContainerCmd(producer.getContainerId()).exec();
            docker.unpauseContainerCmd(replica.getContainerId()).exec();
        }
    }

    @Então("o token antigo não confirma nem reagenda a nova reivindicação")
    public void fencedClaimRetainsItsIdentity() throws Exception {
        assertEquals(1, scalar(propostasDb,
                "SELECT count(*) FROM proposal_outbox WHERE event_id=? AND proposal_id=?", relayEvent, relayProposal));
    }

    @Então("a publicação eventual preserva o evento e um único efeito no cadastro")
    public void eventualDeliveryAndInboxDeduplication() throws Exception {
        waitPublished();
        var published = records(relayProposal);
        assertFalse(published.isEmpty());
        var first = decode(published.getFirst());
        assertEquals(relayEvent, first.getMetadata().getEventId().toString());
        for (var record : published) {
            assertEquals(relayProposal, record.key());
            assertEquals(first, decode(record), "Every attempt must retain the complete original event");
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertEquals(1, scalar(cadastroDb, "SELECT count(*) FROM proposal_inbox WHERE event_id=?", relayEvent));
            assertEquals(1, scalar(cadastroDb, "SELECT count(*) FROM received_proposals WHERE proposal_id=?", relayProposal));
        });
        assertCommitted(published.getLast());
        assertEquals(1, scalar(propostasDb, """
                SELECT count(*) FROM proposal_outbox WHERE event_id=?
                AND published_at IS NOT NULL AND claim_token IS NULL AND claimed_until IS NULL
                """, relayEvent));
    }

    private void rememberRelay() throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("POST:/proposals:" + key).getBytes(StandardCharsets.UTF_8)));
        try (var db = connection(propostasDb); var sql = db.prepareStatement("""
                SELECT p.proposal_id,o.event_id FROM proposals p JOIN proposal_outbox o ON o.proposal_id=p.proposal_id
                WHERE p.idempotency_key_hash=?
                """)) {
            sql.setString(1, hash);
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                relayProposal = rows.getString(1);
                relayEvent = rows.getString(2);
            }
        }
    }

    private void waitPublished() {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertEquals(1, scalar(propostasDb,
                "SELECT count(*) FROM proposal_outbox WHERE event_id=? AND published_at IS NOT NULL", relayEvent)));
    }

    private static HttpRequest request(GenericContainer<?> application, String product, String key) {
        var builder = HttpRequest.newBuilder(URI.create("http://" + application.getHost() + ":"
                + application.getMappedPort(8081) + "/proposals"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString("{\"productKey\":\"" + product + "\"}")).build();
    }

    private String proposalId() throws Exception {
        return JSON.readTree(responses.getFirst().body()).path("proposalId").asText();
    }

    private static String registryUrl() {
        return "http://" + registry.getHost() + ":" + registry.getMappedPort(8081);
    }

    private static String registryRequest(String path, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(registryUrl() + path))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return response.body();
    }

    private static List<ConsumerRecord<String, byte[]>> records(String proposalId) {
        try (var inspector = new KafkaConsumer<String, byte[]>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(), "enable.auto.commit", false,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"))) {
            var partitions = List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1), new TopicPartition(TOPIC, 2));
            inspector.assign(partitions);
            inspector.seekToBeginning(partitions);
            var end = inspector.endOffsets(partitions);
            var result = new ArrayList<ConsumerRecord<String, byte[]>>();
            var deadline = InstantDeadline.after(Duration.ofSeconds(20));
            while (partitions.stream().anyMatch(partition -> inspector.position(partition) < end.get(partition))) {
                assertFalse(deadline.expired(), "Kafka inspection timed out");
                for (var record : inspector.poll(Duration.ofMillis(100))) {
                    if (proposalId.equals(record.key())) {
                        result.add(record);
                    }
                }
            }
            return result;
        }
    }

    private static ProposalCreated decode(ConsumerRecord<String, byte[]> record) throws Exception {
        assertEquals(0, record.value()[0]);
        assertEquals(schemaId, ByteBuffer.wrap(record.value(), 1, 4).getInt());
        return new SpecificDatumReader<>(ProposalCreated.class).read(null,
                DecoderFactory.get().binaryDecoder(record.value(), 5, record.value().length - 5, null));
    }

    private static void assertCommitted(ConsumerRecord<String, byte[]> record) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                var offsets = admin.listConsumerGroupOffsets("insurance-cadastro-v1")
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                var committed = offsets.get(new TopicPartition(TOPIC, record.partition()));
                assertNotNull(committed);
                assertTrue(committed.offset() > record.offset());
            }
        });
    }

    private static Connection connection(PostgreSQLContainer database) throws Exception {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static long scalar(PostgreSQLContainer database, String sql, Object... parameters) throws Exception {
        try (var connection = connection(database); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static void execute(PostgreSQLContainer database, String sql) throws Exception {
        try (var connection = connection(database); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void restart(GenericContainer<?> container) {
        try {
            var logs = Path.of("target", "cucumber", "container-logs");
            Files.createDirectories(logs);
            Files.writeString(logs.resolve("before-restart-" + UUID.randomUUID() + ".log"), container.getLogs());
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot preserve logs before restart", failure);
        }
        // Replace the stateless instance, retaining its PostgreSQL database.
        // Let Testcontainers refresh dynamically assigned host ports and its wait strategy.
        container.stop();
        container.start();
    }
    private record InstantDeadline(long deadlineNanos) {
        static InstantDeadline after(Duration duration) {
            return new InstantDeadline(System.nanoTime() + duration.toNanos());
        }
        boolean expired() {
            return System.nanoTime() > deadlineNanos;
        }
    }
}
