# ADR-0009 — Observabilidade da jornada por Micrometer, OpenTelemetry e OTLP

Status: Aceito. Data: 2026-11-02.
Substitui a instrumentação Java Agent/StatsD dos ADRs 0005/0006 somente em ms-propostas e ms-cadastro.
Preserva integralmente leases, fencing, backoff com equal jitter e Inbox do ADR-0008.

## Contexto

O projeto já tinha Java Agent Datadog e StatsD nos seis serviços, mas faltavam observações de negócio
e correlação durável entre HTTP, Outbox e Kafka. O código usa JdbcTemplate; não migramos para
Spring Data JDBC nem alteramos o SQL de arbitragem/idempotência para atender à descrição da stack.

## Decisão

Usar spring-boot-starter-opentelemetry 4.1.2, que gerencia Micrometer Tracing, bridge OTel,
SDK OpenTelemetry 1.62.0, exporter OTLP e registry Micrometer OTLP.
Não declarar versões duplicadas nem construir manualmente um SDK/exporter.
Código da feature depende das APIs Micrometer, sem SDK Datadog.

Remover dd-java-agent e StatsD somente dos dois serviços da jornada, evitando dois SDKs de tracing
na mesma JVM. Os outros quatro permanecem como estavam: Java Agent 1.68.0 e StatsD.
Spring AOT/JVM AOT Cache continuam nos seis; as duas imagens precisam ser reconstruídas.

Exportar métricas e traces OTLP HTTP/protobuf ao Agent 7.85.0 em datadog-agent:4318.
Logs permanecem JSON em stdout e são coletados seletivamente pelo Agent via Docker.
Não exportar logs também por OTLP. A chave Datadog fica exclusivamente no Agent.

## Contexto durável

V3 adiciona traceparent/tracestate nullable à Outbox, sem alterar V1/V2 nem o Avro.
Na primeira criação, contexto W3C do span de registro é gravado junto da intenção na mesma transação.
Replay idempotente não substitui contexto nem IDs originais.

Relay extrai o contexto em uma Observation ReceiverContext de tipo CONSUMER, depois do commit da claim.
Cada tentativa cria novo span, preservando o trace e o eventId originais.
KafkaTemplate/listener usam Observation nativa, que injeta/extrai headers W3C.
A observação de consumo envolve validação, transação Inbox e acknowledgment na mesma thread.

Registros antigos sem contexto iniciam outro trace. journeyId/correlationId continuam permitindo
investigação. Um journeyId pode abranger múltiplos traces; traceId não é identidade de negócio.

## Métricas e logs

Observações: insurance.proposal.register, insurance.outbox.publish e insurance.proposal.consume.
IDs e productKey são high-cardinality attributes de spans, nunca dimensões de métricas.
Dimensões customizadas são apenas enums limitados, como outcome, stage, journeyType e eventType.

Contar registros somente após commit. O caso de uso transacional do cadastro retorna um boolean
interno para distinguir novo efeito/duplicata; o listener recebe esse resultado após o commit.
Inbox, ACK e contrato público permanecem iguais.

Backlog/idade são coletados por leitura agregada periódica, fora do caminho de envio.
Exporter lê snapshot em memória, sem acessar JDBC. Falha de leitura gera NaN e available=0.
Com réplicas sobre o mesmo banco, backlog/idade devem ser agregados por máximo, não somados.

MDC de trace é gerenciado por escopos Micrometer e limpo ao fechá-los.
Confirmação assíncrona Kafka reabre o escopo capturado somente para o log.
JSON renomeia traceId/spanId para trace_id/span_id, reconhecidos pelo Datadog.
service é definido globalmente no formatter, evitando campos duplicados pelo JsonWriter.

## Alternativas

Manter dois agentes/SDKs na mesma JVM poderia duplicar spans e exportações.
Colocar contexto no Avro mudaria o contrato sem necessidade: headers e colunas técnicas resolvem.
Usar IDs em métricas geraria cardinalidade por evento; logs/spans servem à investigação individual.
Tornar o envio de telemetria síncrono acoplaria a jornada à disponibilidade do backend.

## Consequências e limites

Métricas são evidência operacional best effort, não ledger financeiro ou contagem exactly-once.
SDK/exporter possui filas limitadas; falhas prolongadas podem perder telemetria.
Isso não altera publicação eventual, eventId, retries, fencing ou efeitos da Inbox.

Latência de entrega mede occurredAt até retorno do commit do cadastro; duplicatas não repetem a amostra.
Depende de relógios sincronizados e ignora durações negativas. Traces atrasados podem aparecer como
traces parciais conforme a janela de ingestão; IDs de negócio continuam independentes.

Sampling padrão 100% é para esta PoC e parametrizável. Não houve benchmark novo de desempenho.
Virtual Threads seguem desativadas; não foram criados executores manuais de produção.

Agent mantém portas administrativas internas à rede Docker. Logs são limitados a propostas/cadastro.
HOST_PROC=/proc aplica workaround oficial de ingestão OTLP no Docker; health verifica também a porta 4318.
No Docker Desktop, host refere-se à VM Linux, não ao Windows físico.

## Validação e referências

[Resultado executado](../observability-validation.md).
BDD cobre continuidade de trace, headers/colunas, logs, cardinalidade e backend OTLP indisponível,
além dos 13 cenários anteriores de confiabilidade.

Referências oficiais:
[Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html),
[Micrometer OTLP](https://docs.spring.io/spring-boot/reference/actuator/metrics.html#actuator.metrics.export.otlp),
[Kafka Observation](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html),
[Agent OTLP e workaround Docker](https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/),
[Logs e traces OpenTelemetry](https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/opentelemetry/).
