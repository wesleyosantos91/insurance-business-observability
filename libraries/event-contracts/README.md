# event-contracts

Biblioteca Maven sem Spring Boot, producers, consumers ou lógica de negócio.
Gera SpecificRecords Java pelo avro-maven-plugin 1.12.1 na fase generate-sources.

A única fonte editável do contrato é
[infrastructure/kafka/schemas/proposals/proposal-created-v1.avsc](../../infrastructure/kafka/schemas/proposals/proposal-created-v1.avsc).
Não copie o .avsc para o módulo nem edite target/generated-sources/avro.

Classes geradas no namespace io.github.wesleyosantos91.insurance.events.proposals.v1:

- ProposalCreated
- EventMetadata
- ProposalCreatedPayload

~~~powershell
mvn -pl libraries/event-contracts -am clean verify
~~~

Os testes verificam geração, serialização e desserialização Avro.
O JAR é consumido por ms-propostas e ms-cadastro. Artefato Maven com groupId legado
io.github.wesleosantos91, versão 1.0.0-SNAPSHOT; packages preservam o namespace oficial.

A biblioteca define o contrato de transporte, não um modelo compartilhado de domínio.
Registro operacional, subject TopicRecordNameStrategy e BACKWARD_TRANSITIVE estão documentados
no [Kafka](../../infrastructure/kafka/README.md) e no [ADR-0002](../../docs/adr/0002-kafka-avro.md).
