> Registro de uma etapa anterior. Para o estado atual, consulte [documentação](README.md), [ADRs](adr/README.md) e [validação Datadog](datadog-validation.md).

# Validação — integração Java Kafka/Avro

Execução local em 01/11/2026. Maven 3.10.0, Eclipse Temurin 27+35 (GA), Spring Boot 4.1.2, Spring Framework 7.0.10, Spring Kafka 4.1.2, Kafka Clients 4.2.2, Avro 1.12.1 e Confluent SerDes 8.1.1.

## Maven Reactor

Comando executado: mvn -B -ntp clean verify, equivalente batch ao mvn clean verify solicitado.
Resultado: BUILD SUCCESS em 27,386 s. Compilação release 27; geração dos três SpecificRecords antes da compilação.

| Projeto | Resultado | Testes | Falhas / erros / ignorados |
|---|---|---:|---|
| insurance-business-observability | SUCCESS | — | — |
| event-contracts | SUCCESS | 3 | 0 / 0 / 0 |
| ms-propostas | SUCCESS | 19 | 0 / 0 / 0 |
| ms-cadastro | SUCCESS | 17 | 0 / 0 / 0 |
| ms-analise | SUCCESS | 1 | 0 / 0 / 0 |
| ms-contratos | SUCCESS | 1 | 0 / 0 / 0 |
| ms-endossos | SUCCESS | 1 | 0 / 0 / 0 |
| ms-pagamentos | SUCCESS | 1 | 0 / 0 / 0 |
| Total | SUCCESS | 43 | 0 / 0 / 0 |

A contagem foi obtida dos XMLs Surefire de cada módulo. Testes não exigem Kafka ou Registry externos. Cobertura inclui IDs/Clock fixo, construção do contrato, wire format Confluent, SpecificRecord, subject ausente sem auto-registro, bytes inválidos no ErrorHandlingDeserializer, chave, confirmação assíncrona, falhas, timeout sem cancelar publicação, HTTP/ProblemDetail, correlação, validação e ordem processamento → acknowledgment.

Log Maven completo: C:\Users\ADMINI~1\AppData\Local\Temp\insurance-integration-verify.log

## Integração real

Comando executado duas vezes com sucesso após os ajustes do harness: ./tests/integration/proposal-flow.ps1.
Os ENTRYPOINTs das imagens AOT foram utilizados, com portas de teste 18081/18082 e listeners internos kafka:29092 / schema-registry:8081.

| Verificação | Resultado |
|---|---|
| HTTP inválido | 400, application/problem+json |
| POST /proposals válido | 202, status ACCEPTED após confirmação |
| Transporte | Confluent wire header: magic byte 0 + Schema ID + Avro binário |
| Verificação independente | Python Avro decodificou bytes reais do Kafka no container kafka-tools |
| Subject | insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated |
| Schema ID / Registry version | 1 / 1; nenhuma versão registrada pelo Java |
| Compatibilidade | BACKWARD_TRANSITIVE preservada, verificada via API |
| Consumer group | insurance-cadastro-v1, confirmado no broker |
| Chave Kafka | proposalId, conferida contra a resposta e o payload |
| Correlação | eventId, journeyId, rootJourneyId e correlationId preservados |
| Posição | insurance.proposals.events.v1, partição 2, offset 1 |
| Offset confirmado | CURRENT-OFFSET=2 para a partição 2, superior ao offset 1 processado; lag=0 |
| Reexecução | Novo evento, mesmo contrato registrado, sem auto-registro |

Evidência da última execução:

~~~json
{
  "result": "AVRO_CONFIRMED",
  "topic": "insurance.proposals.events.v1",
  "partition": 2,
  "offset": 1,
  "key": "f8d60507-eac6-4add-a139-75cc1b0864ae",
  "schemaId": 1,
  "registryVersion": 1,
  "subject": "insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated",
  "compatibility": "BACKWARD_TRANSITIVE",
  "eventId": "cceb87e4-52cc-4cab-abfc-a31896ac9951",
  "eventType": "ProposalCreated",
  "schemaVersion": "1.0.0",
  "occurredAt": "2026-11-01T23:01:40.143602535Z",
  "productKey": "AUTO",
  "journeyType": "PROPOSAL",
  "journeyId": "38e3a19a-2738-44c7-8db2-c107f38f81cd",
  "rootJourneyId": "38e3a19a-2738-44c7-8db2-c107f38f81cd",
  "correlationId": "cf869485-9eb8-4f3e-972c-ea14608f7822",
  "parentJourneyId": null,
  "causationId": null,
  "proposalId": "f8d60507-eac6-4add-a139-75cc1b0864ae"
}
~~~

O log do cadastro contém "ProposalCreated processed and acknowledged" para o mesmo proposalId e a mesma posição. O teste compara seus campos com a inspeção independente e consulta o commit do grupo depois do processamento.

Arquivos completos: C:\Users\Administrador\AppData\Local\Temp\insurance-proposal-flow-20261101-200134

- http-response.json
- avro-inspection.json
- consumer-confirmation.json
- consumer-group-offsets.txt
- Logs dos dois containers

## Spring AOT e JVM AOT Cache

Ambos os docker builds terminaram com sucesso, sem conexão obrigatória com broker/Registry durante geração Spring AOT, testes e treinamento de startup.

| Imagem | Digest local | Cache produzido |
|---|---|---:|
| insurance/ms-propostas:kafka-aot | sha256:2f5753afc911c116d8e49a38d1cc2f7a2920c7dc5d6e85c0f3b36d7a02c8625e | 72.384.512 bytes |
| insurance/ms-cadastro:kafka-aot | sha256:41ea170c975bb58fdf19185034dbf4555462ab9d8e17bbaac95df30bf4303847 | 72.392.704 bytes |

Logs de build:

- C:\Users\ADMINI~1\AppData\Local\Temp\insurance-propostas-docker.log
- C:\Users\ADMINI~1\AppData\Local\Temp\insurance-cadastro-docker.log

Durante o teste real, JAVA_TOOL_OPTIONS=-Xlog:aot=info confirmou nos dois runtimes:

~~~text
Opened AOT cache application.aot.
Mapped static region #0 ... (ReadWrite)
Mapped static region #1 ... (ReadOnly)
~~~

Os logs Spring também indicam "Starting AOT-processed". O teste posterior ao treinamento publicou/desserializou Avro e confirmou offsets com as otimizações habilitadas. Não é um benchmark de cold start nem validação GraalVM.

## Concorrência

Virtual Threads não foram adotadas. A tabela por componente e a avaliação de CPU, I/O, contenção, suporte nativo e acknowledgment estão em kafka-java-integration.md.

Sem APIs preview, executors manuais, programação reativa, get/join de produção ou paralelização do consumer. Confirmações são compostas por CompletableFuture e o ack permanece na thread do listener.

Não se executou benchmark VT versus platform threads porque VT não foi introduzida; nenhum ganho de throughput, p95/p99, CPU ou memória foi afirmado.

## Arquivos preexistentes modificados

- pom.xml
- services/ms-cadastro/Dockerfile
- services/ms-cadastro/pom.xml
- services/ms-cadastro/src/main/resources/application.yaml
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/CadastroApplicationTests.java
- services/ms-propostas/Dockerfile
- services/ms-propostas/pom.xml
- services/ms-propostas/src/main/resources/application.yaml

O POM raiz somente agrega libraries/event-contracts. Nos POMs de propostas/cadastro foram acrescentados event-contracts, Confluent SerDes e o repositório Confluent, preservando o parent Boot e o groupId. YAMLs configuram mensageria, timeouts e logs nativos. O teste de contexto do cadastro desativa apenas o auto-start do listener. Os dois Dockerfiles mantêm seus estágios AOT/cache e adaptam o contexto Maven à raiz.

## Arquivos criados

- .dockerignore
- docs/kafka-java-integration.md
- docs/kafka-java-validation.md
- libraries/event-contracts/pom.xml
- libraries/event-contracts/src/test/java/io/github/wesleyosantos91/insurance/events/proposals/v1/ProposalCreatedContractTests.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/config/ProposalConsumerConfiguration.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/config/ProposalConsumerProperties.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ProcessProposalCreatedUseCase.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ReceivedProposal.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalConsumerErrorHandler.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalCreatedConsumer.java
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalCreatedConsumerTests.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/config/ProposalConfiguration.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/config/ProposalMessagingProperties.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/CreateProposalRequest.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/CreateProposalResponse.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalController.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalExceptionHandler.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/application/CreatedProposal.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/application/CreateProposalUseCase.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalEventPublisher.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/api/ProposalControllerTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/application/CreateProposalUseCaseTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalAvroSerializationTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalEventPublisherTests.java
- tests/integration/proposal-flow.ps1
- tests/integration/verify-proposal.py

As três classes Avro são geradas em target/generated-sources/avro e não são arquivos editáveis versionados. O schema original não foi copiado.

## Preservação e verificações finais

- SHA-256 comparado com a base anterior: os outros quatro serviços, toda a infraestrutura e o .avsc original permanecem idênticos.
- git diff --check: sem erros. Como todos os arquivos do repositório já estavam untracked, verificou-se também whitespace de todos os arquivos não ignorados: zero ocorrências.
- git status consultado: alterações anteriores preservadas; sem commit ou push.
- docker compose config --quiet: aprovado; Kafka, Schema Registry e UI mantidos saudáveis.
- Somente containers temporários criados pelo teste foram removidos; nenhum volume foi apagado. Processos de aplicação que já existiam no host não foram substituídos.

## Problemas encontrados e limites

- O PowerShell recebeu JSON do Actuator como bytes e atingiu timeouts curtos usando localhost; o harness decodifica UTF-8, usa IPv4 explícito e preserva o último erro de saúde para diagnóstico.
- O Registry em memória mantém entradas internas durante lookup de um subject ausente; a asserção verifica ausência de versão registrada (40401), sem confundir cache com registro de schema.
- Avisos não fatais: uso transitivo de Unsafe pelo Guava no Java 27; auto-attach de Mockito nos testes; preload AOT de classes opcionais fora do classpath. Não foram adicionados frameworks para suprimir esses avisos.
- Entrega at-least-once; não há idempotência persistente. Um timeout HTTP pode coexistir com publicação concluída posteriormente.
- Sem DLQ nesta etapa: evento inválido causa parada observável do listener, sem confirmação do registro falho; correção operacional é necessária antes da retomada.
- Saúde HTTP não comprova sozinha que o listener continua ativo. Monitoramento de lifecycle e política de recuperação são próximos passos.
- Sem benchmark de desempenho, sem alegação de ganho percentual de cold start e sem garantia de ausência de pinning em bibliotecas não perfiladas.

Próximos passos recomendados: idempotência HTTP/consumer, tratamento operacional de eventos inválidos e testes de carga representativos antes de alterar concorrência; propagação W3C de trace context na etapa de observabilidade.
