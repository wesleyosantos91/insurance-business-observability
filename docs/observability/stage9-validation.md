# Validação — complemento da Etapa 9

Data: 2026-11-02. Repositório insurance-business-observability, branch develop.
Java Temurin 27+35, Maven 3.10.0, Spring Boot 4.1.2 / Framework 7.0.10,
Micrometer 1.17.2 / Tracing 1.7.2, OpenTelemetry 1.62.0 e Agent 7.85.0.

## Diagnóstico e alterações

A pipeline Micrometer/OTel/OTLP, W3C persistido na Outbox, Kafka Observation nativo, logs JSON,
Agent e BDD já existiam. O trabalho completou nomes canônicos, classificação de conflitos/falhas,
projeção businessId, etapas/status de spans e campos JSON em snake_case. Não reimplementou
Outbox/Inbox ou comunicação Kafka. Nenhuma dependência foi adicionada; foi removida apenas a
declaração duplicada do starter OTel no profile integration de propostas.

SQL, migrações, contrato Avro, leases/fencing, retry com equal jitter, IDs, ACK, endpoint HTTP,
Dockerfiles e os quatro serviços fora da jornada foram preservados. Observações existentes
alimentam timers padronizados via MeterFilter; não há spans adicionais redundantes.

## Resultados executados

~~~powershell
mvn -B -ntp clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
mvn -B -ntp -Pmutation verify
~~~

| Módulo | Testes regulares | Linhas | Branches | Mutantes KILLED | Reactor |
|---|---:|---:|---:|---:|---|
| event-contracts | 3 | Gerado | Gerado | Não aplicável | SUCCESS |
| ms-propostas | 86 | 352/352 | 101/101 | 117/117 | SUCCESS |
| ms-cadastro | 40 | 166/166 | 41/41 | 32/32 | SUCCESS |
| ms-analise | 1 | 3/3 | Não aplicável | Fora do escopo | SUCCESS |
| ms-contratos | 1 | 3/3 | Não aplicável | Fora do escopo | SUCCESS |
| ms-endossos | 1 | 3/3 | Não aplicável | Fora do escopo | SUCCESS |
| ms-pagamentos | 1 | 3/3 | Não aplicável | Fora do escopo | SUCCESS |

- **133 testes regulares aprovados**, zero falhas/erros/ignorados.
- **15 cenários BDD aprovados**, com Kafka, Schema Registry e PostgreSQL reais em Testcontainers.
- BUILD SUCCESS no Reactor completo, inclusive no profile mutation.
- JaCoCo: 100% de linhas e branches disponíveis, sem exclusões adicionais.
- PITest: **117/117 em propostas e 32/32 em cadastro**, sem sobreviventes, timeouts ou erros.
  Cobertura PITest: 326/326 e 146/146 linhas nas classes mutadas.
- A asserção de criações verifica o incremento antes do replay e a ausência de transição para
  replay isolado, detectando inversão da condição que um total final agregado poderia esconder.
- Checkstyle obrigatório passou. Nenhuma meta de qualidade foi reduzida.
- Duas imagens multi-stage reconstruídas com Spring AOT e JVM AOT Cache; iniciadas no BDD.
  Treinamento não exigiu dependência externa; Dockerfiles/UID não foram alterados.

## Evidência de exportação

Captura OTLP HTTP/protobuf real do BDD: **185 lotes de traces,
144 lotes de métricas, 996 spans e 20860 registros de métricas**.
Os números incluem toda a suíte e instrumentações nativas; não são contagens de propostas.
Serviços identificados: ms-propostas e ms-cadastro.

| Nome efetivo OTLP | Tipo capturado | Unidade |
|---|---|---|
| insurance.business.transitions | Sum | Sem unidade |
| insurance.business.step.duration | Histogram | milliseconds |
| insurance.outbox.publish.attempts | Sum | Sem unidade |
| insurance.outbox.publish.duration | Histogram | milliseconds |
| insurance.inbox.duplicates | Sum | Sem unidade |

O teste verifica os cinco nomes, dimensões sem IDs individuais, atributos businessId/stepKey/status,
trace W3C conhecido e logs JSON com business_id/journey_id/event_id/correlation_id, etapa,
resultado, trace_id e span_id hexadecimal. Reentrega Avro mantém um único efeito durável.
O cenário com destino OTLP retornando 503 mantém a entrega de negócio e o ACK após commit.

Agent oficial em rede interna, chave fictícia e intake local: PASS. Foram recebidas requisições
/api/v2/series, /api/beta/sketches, /api/v0.2/traces e /api/v2/logs; os IDs de trace do log foram
preservados. Há 3 logs recebidos no intake desse teste. A pipeline de logs é Docker/stdout,
com exportação OTLP de logs desabilitada. Não se usou a chave real no teste.

## Artefatos locais e reprodução

- tests/integration/target/stage9-bdd/failsafe-reports e cucumber: suíte/relatórios arquivados.
- tests/integration/target/observability/summary.json, metrics.pb, traces.pb e metric-descriptors.json.
- tests/integration/target/observability/container-logs: logs JSON das aplicações de teste.
- tests/integration/target/datadog-agent/local-intake.json e agent-status.txt: forwarding do Agent.
- services/ms-*/target/site/jacoco e target/pit-reports: cobertura/mutação.

Targets são ignorados pelo Git. Arquivos de evidência não contêm chaves reais.

## Limites e pendências

Não foi executada consulta de uma proposta concreta no backend SaaS nesta etapa. Portanto não
se declara indexação, retenção ou busca no Datadog como aprovadas. O teste demonstra exportação
OTLP e encaminhamento local pelo Agent. Também não se executou uma nova análise Sonar nesta
etapa; as políticas Maven existentes foram exercitadas integralmente.

Tracing/exportação é best effort; filas e indisponibilidade prolongada podem perder telemetria.
Uma janela pós-commit pode perder métricas sem perder os dados. Sampling/retention podem tornar
traces parciais. Nenhuma métrica substitui o banco operacional ou fornece exactly-once.
Amostragem local 100% deve ser revista conforme quotas e custos da conta.

Avisos existentes de auto-attach Mockito/Byte Buddy/Unsafe e metadata de repositórios Flyway
com HTTP 401 não impediram os builds. Não foi habilitado preview ou relaxada configuração.
Não houve git commit/push, limpeza de volumes existentes ou alteração de segredos locais.

[Contrato](business-telemetry-contract.md), [operação](datadog-integration.md) e
[ADR-0012](../adr/0012-business-telemetry-contract.md).

## Arquivos criados e modificados

Criados:

- docs/adr/0012-business-telemetry-contract.md
- docs/observability/business-telemetry-contract.md
- docs/observability/datadog-integration.md
- docs/observability/stage9-validation.md
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/config/BusinessTelemetryConfiguration.java
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/config/BusinessTelemetryConfigurationTests.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/config/BusinessTelemetryConfiguration.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/config/BusinessTelemetryConfigurationTests.java

Modificados:

- README.md
- docs/README.md
- docs/adr/README.md
- docs/observability.md
- infrastructure/datadog/README.md
- services/ms-cadastro/README.md
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/application/ProcessProposalCreatedUseCase.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalCreatedConsumer.java
- services/ms-cadastro/src/main/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/observability/ProposalTelemetry.java
- services/ms-cadastro/src/main/resources/application.yaml
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/messaging/ProposalConsumerTelemetryTests.java
- services/ms-cadastro/src/test/java/io/github/wesleyosantos91/cadastro/proposal/infrastructure/observability/ProposalTelemetryTests.java
- services/ms-propostas/README.md
- services/ms-propostas/pom.xml
- services/ms-propostas/src/integration-test/java/io/github/wesleyosantos91/proposta/bdd/IdempotencySteps.java
- services/ms-propostas/src/integration-test/resources/features/observability.feature
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalEventPublisher.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/messaging/ProposalOutboxDispatcher.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/observability/ProposalTelemetry.java
- services/ms-propostas/src/main/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/ProposalStore.java
- services/ms-propostas/src/main/resources/application.yaml
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/observability/ProposalTelemetryTests.java
- services/ms-propostas/src/test/java/io/github/wesleyosantos91/proposta/proposal/infrastructure/persistence/ProposalStoreTests.java
