> Contrato canônico atual: [métricas e correlação](observability/business-telemetry-contract.md); [operação](observability/datadog-integration.md).

# Observabilidade da primeira jornada

## Arquitetura

~~~mermaid
flowchart LR
    HTTP["POST /proposals"] --> P["ms-propostas"]
    P --> DB[("Proposta + Outbox + W3C")]
    DB --> R["Outbox Relay: lease + fencing"]
    R -->|"Avro + headers W3C"| K["Kafka"]
    K --> C["ms-cadastro"]
    C --> I[("Inbox + recebimento")]
    P & R & C -->|"Micrometer / OTel: OTLP HTTP"| A["Datadog Agent :4318"]
    P & C -->|"stdout JSON / Docker"| A
    A --> DD["Datadog APM / Metrics / Logs"]
~~~

Os dois serviços usam o starter OpenTelemetry nativo do Boot. Nenhum Datadog Java Agent executa
nessas JVMs. Os quatro serviços de fundação continuam com Java Agent/StatsD.
Não existem dois SDKs de tracing/exporters concorrendo no mesmo serviço.

O Agent recebe OTLP HTTP/protobuf em /v1/traces e /v1/metrics, somente na rede Docker.
Não há novo Collector obrigatório em produção. A captura OTLP dos testes é um test double local.

## Correlacionar HTTP, Outbox e Kafka

- Entrada HTTP aceita W3C Trace Context pela instrumentação nativa Spring MVC.
- Observation insurance.proposal.register cria span e grava traceparent/tracestate na Outbox.
- Contexto é gravado apenas na criação, na mesma transação da proposta e intenção.
- O relay reivindica o registro, confirma a claim e só então reabre o contexto persistido.
- Observation insurance.outbox.publish acompanha cada tentativa, sem transação JDBC durante a rede.
- Observation nativa do KafkaTemplate injeta traceparent/tracestate nos headers.
- Observation nativa do listener extrai os headers e a observação de negócio envolve consumo/commit/ack.
- Falha e retomada preservam eventId e conteúdo Avro. A Inbox mantém um efeito local.

O contexto não entra no fingerprint da Inbox. Headers de tentativas distintas podem apontar para spans
distintos, sem transformar o mesmo evento em outro conteúdo de negócio.
Rows antigas sem W3C iniciam novo trace, mantendo correlação pelos IDs de negócio.
Repetir a intenção HTTP pode produzir outro trace de request; a Outbox mantém o contexto original.

## Catálogo de métricas

| Nome OTLP | Tipo | Dimensões customizadas | Interpretação |
|---|---|---|---|
| insurance.proposal.registrations | Counter diagnóstico | outcome=created/replayed/rejected/technical_failure | Resultado do registro; criações/replays após commit, falhas/rejeições após rollback; não é quantidade de HTTP 202 |
| insurance.business.step.duration | Timer/Observation, step=proposal_register | journey_type, outcome, error_code | Tempo de registro, incluindo commit |
| insurance.outbox.dispatches | Counter | outcome=published/retry/claim_lost/database_failure | Transições observadas pelo relay |
| insurance.outbox.publish.duration | Timer/Observation | journey_type, outcome, error_code | Envio e confirmação da tentativa |
| insurance.outbox.pending | Gauge | Identidade técnica do serviço | Pendências globais do banco |
| insurance.outbox.oldest.age | Gauge, segundos | Identidade técnica do serviço | Idade da pendência mais antiga |
| insurance.outbox.snapshot.available | Gauge | Identidade técnica do serviço | 1=consulta disponível; 0=indisponível |
| insurance.proposal.receipts | Counter | outcome=new/duplicate | Efeito novo ou deduplicado após commit Inbox |
| insurance.business.step.duration | Timer/Observation, step=proposal_receive | journey_type, outcome, error_code | Validação, processamento e ACK |
| insurance.proposal.consume.failures | Counter | stage=validation/processing/acknowledgment | Falha antes da conclusão do listener |
| insurance.proposal.delivery.latency | Timer | Identidade técnica do serviço | occurredAt até commit do primeiro recebimento |

Timers exportam a unidade configurada pelo registry OTLP; a captura atual comprovou milliseconds.
oldest.age possui unidade explícita segundos. Histogramas são habilitados para tempos HTTP e da jornada.
O Agent configura distribuição a partir dos histogramas; confirme nomes/unidades finais no Metrics Explorer.

Continuam disponíveis métricas nativas HTTP, JVM, Hikari, Kafka e observações de transporte.
Os meters *.active são instrumentos nativos derivados de Observation, não novos eventos de negócio.

Regras:

- Nunca usar eventId, proposalId, journeyId, correlationId, productKey ou Idempotency-Key como tags.
- productKey aceita conteúdo aberto na API atual; fica somente em span attributes.
- Em réplicas sobre o mesmo banco, use max para backlog/idade; não some snapshots repetidos.
- available=0/NaN significa leitura indisponível, não backlog zero.
- Duplicatas incrementam duplicate, não new, e não repetem a amostra de latência.
- Counters não substituem consultas de negócio/auditoria no PostgreSQL; exportação não é exactly-once.

## Investigar uma proposta no Datadog

1. Em Logs Explorer, filtre service:ms-propostas e @proposal_id:<id>.
2. Use journeyId, rootJourneyId, correlationId e eventId para acompanhar as etapas.
3. Abra o trace a partir de trace_id/span_id presentes no JSON.
4. No APM, procure os spans insurance.proposal.register, insurance.outbox.publish e insurance.proposal.consume.
5. Diferencie outcome=retry/database_failure de uma publicação confirmada.
6. Para consumer, compare new/duplicate, logs de ACK e offset; ACK não torna o fluxo exactly-once.

Trace ID em JSON é string hexadecimal; não converta para número nem descarte os 128 bits.
O Agent/pipeline Datadog reconhece trace_id e span_id padrão OpenTelemetry.
Filtros por atributos customizados no APM podem exigir configuração de facetas/indexação na conta.
Não foram criados dashboards ou monitores automaticamente.

Exemplos de acompanhamento técnico: taxa de dispatches retry, backlog crescente, oldest.age crescente,
consume.failures por etapa e latência de entrega. A unidade e o nome normalizado devem ser conferidos
na conta real; não foi validada uma query SaaS sem credencial.

## Configuração

| Variável | Padrão | Uso |
|---|---|---|
| DD_SERVICE / OTEL_SERVICE_NAME | Nome do módulo | Devem identificar o mesmo serviço |
| DD_ENV | local | Ambiente |
| DD_VERSION | 0.0.1-SNAPSHOT | Versão do serviço |
| OTEL_EXPORTER_OTLP_ENDPOINT | http://localhost:4318 | Base OTLP no host; Compose usa datadog-agent |
| OTEL_EXPORTER_OTLP_TRACES_ENDPOINT | Base + /v1/traces | Override completo opcional |
| OTEL_EXPORTER_OTLP_METRICS_ENDPOINT | Base + /v1/metrics | Override completo opcional |
| OTLP_METRICS_STEP | 10s | Intervalo de métricas |
| TRACING_SAMPLING_PROBABILITY | 1.0 | Sampling da PoC |
| DD_API_KEY / DD_SITE | Somente Agent | Credencial e site Datadog |

O Compose define resource attributes service.name, service.version e deployment.environment.name,
com mapeamento de ambiente para o Datadog. Não envie IDs de negócio em baggage global.
SDK/exportação é assíncrono e limitado; backend indisponível não bloqueia commit/publicação.

## Testar e operar

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
./tests/integration/datadog-metrics.ps1
~~~

BDD possui 15 cenários: 13 de confiabilidade e dois de observabilidade.
O teste do Agent usa os payloads OTLP e um log correlacionado produzidos pelo BDD.
Os payloads ficam em tests/integration/target/observability. O log é lido do target/cucumber do módulo ou, após clean, de tests/integration/target/observability/container-logs quando arquivado. Preserve essas evidências; elas são ignoradas pelo Git.

Veja [infraestrutura Datadog](../infrastructure/datadog/README.md), [ADR-0009](adr/0009-journey-otel-otlp.md)
e [relatório](observability-validation.md). A ingestão SaaS exige sua chave local e o site correto.
