# Validação — Etapa 9: Micrometer, OpenTelemetry e Datadog

Data: 2026-11-02. Ambiente: Windows, PowerShell 7 e Docker Desktop Linux amd64.

## Resultado

Jornada ms-propostas → Outbox Relay → Kafka → ms-cadastro instrumentada com
Micrometer Observation/Metrics/Tracing e SDK OpenTelemetry. Traces e métricas usam OTLP/HTTP;
logs JSON em stdout são coletados seletivamente pelo Agent oficial.

| Validação executada | Resultado |
|---|---|
| mvn -B -ntp clean verify | BUILD SUCCESS, 63 testes; 0 falhas, erros ou ignorados; 49,603 s |
| mvn -B -ntp -Pintegration clean verify | BUILD SUCCESS, 63 testes regulares + 15 cenários BDD; 0 falhas, erros ou ignorados; 2 min 30 s |
| Imagens de propostas e cadastro | Build multi-stage, geração Avro, Spring AOT, treinamento JVM AOT Cache e runtime aprovados |
| Quatro serviços de fundação | Health, métricas JVM/HTTP, service/env/version e uso de Spring AOT/JVM AOT Cache aprovados |
| docker compose config | Configuração Datadog válida; serviços exclusivos e receptor OTLP interno |
| Agent 7.85.0 em rede isolada | Saudável; OTLP traces/metrics HTTP 200, DogStatsD e encaminhamento local de logs correlacionados aprovados |
| Reexecução do teste Agent após clean | Aprovada utilizando evidências arquivadas dos containers |

Os 63 testes regulares se distribuem em event-contracts (3), ms-propostas (34), ms-cadastro (22)
e um teste em cada um dos outros quatro serviços. O Reactor reconheceu todos os sete módulos e o agregador.

## Evidências de integração

Os 13 cenários anteriores de confiabilidade continuam aprovados: repetição/conflito de
Idempotency-Key, concorrência entre réplicas, indisponibilidade Kafka, retry exponencial com
equal jitter persistido, falha após ACK antes do commit, leases/fencing, crash/restart,
deduplicação da Inbox, rollback e validação HTTP.

Dois novos cenários verificam:

1. Requisição W3C → contexto persistido na Outbox → headers Kafka → consumo correlacionado.
   Também validam Avro binário, logs JSON e ausência de IDs de negócio/productKey nas dimensões de métricas.
2. Exporter retornando HTTP 503 sem impedir HTTP 202, confirmação Kafka e persistência Outbox/Inbox.

A captura local recebeu 858 spans e 16.405 registros de métricas em 158 lotes de traces
e 115 lotes de métricas, de ms-propostas e ms-cadastro. Esses números descrevem a execução
da suíte, não um benchmark ou contagem de propostas únicas.

O teste seguinte reenviou protobuf real ao Agent oficial e confirmou encaminhamento para intake
HTTP local em /api/v0.2/traces, /api/v2/series e /api/v2/logs, preservando trace_id no log.
A rede Docker --internal e a chave fictícia impediram envio para a conta SaaS.

Evidências geradas e ignoradas pelo Git:

- tests/integration/target/observability: summary.json, traces.pb, metrics.pb, bdd.html, bdd.json, relatórios XML e container-logs.
- tests/integration/target/datadog-agent: agent-health.txt, agent-status.txt, agent.log e local-intake.json.
- tests/integration/target/datadog: validação dos quatro serviços de fundação.

## Arquivos e decisões

Criados:

- docs/adr/0009-journey-otel-otlp.md, docs/observability.md e este relatório.
- ProposalTelemetry.java nos packages proposal.infrastructure.observability de propostas e cadastro.
- OutboxMetrics.java em propostas e V3__outbox_trace_context.sql.
- ProposalTelemetryTests.java nos dois serviços.
- OtlpCapture.java e features/observability.feature na suíte BDD de propostas.

Alterados:

- POMs, application.yaml e Dockerfiles apenas de propostas/cadastro: starter nativo OpenTelemetry,
  OTLP, logs estruturados e remoção do segundo agente de tracing.
- ProposalStore, StoredProposal e seleção do ProposalOutboxStore: contexto W3C persistido atomicamente.
- Dispatcher/publisher: escopos de observação e resultados limitados; confirmação Kafka e fencing preservados.
- Consumer/use case/configuração: métricas após commit, correlação e reconhecimento na thread original.
- Testes afetados, IdempotencySteps e scripts Datadog: captura real e validação sem conta externa.
- Compose/.env.example Datadog, READMEs e documentação/índice de ADRs.
- .gitignore: ignora variantes locais .env.*, preservando exemplos publicáveis.

A comparação SHA-256 com a inspeção inicial confirmou preservação do schema Avro, migrações V1/V2
e código/POM/Dockerfile/configuração dos quatro outros serviços. O groupId Maven legado e
o uso existente de JdbcTemplate foram mantidos.

O contexto de trace é adicional ao contrato Avro; IDs de negócio continuam independentes de traceId.
IDs ficam em logs/atributos de spans, nunca como tags de métricas. Backlog é snapshot por réplica:
agregue com max, não sum. Telemetria não participa da atomicidade do negócio e pode perder dados.

## Concorrência

| Componente | Natureza | Virtual Threads | Decisão |
|---|---|---|---|
| Spring MVC e registro JDBC | I/O JDBC bloqueante curto; resposta futura assíncrona | Desativadas | Preservado modelo atual; sem evidência de benefício que justifique troca nesta etapa |
| KafkaTemplate e confirmação | Envio/ACK assíncronos | Descartadas | Composição existente; sem envolver send em executor adicional |
| Relay agendado | JDBC curto + espera de ACK fora da transação | Desativadas | Preserva leases, limite de concorrência e fencing |
| Listener cadastro | Thread de partição + transação JDBC | Descartadas | ACK permanece após commit na mesma thread; paralelismo limitado pelas partições |
| Exporters OTLP | Processamento em lote e I/O do SDK | Descartadas | Gerenciamento nativo de threads do SDK |
| Snapshot de backlog | Consulta JDBC periódica | Desativadas | Snapshot em memória; nenhuma consulta na thread de exportação |

Não foram usados recursos preview, executores manuais em produção ou benchmark comparativo
de Virtual Threads. Não se afirma ganho de throughput/p95/p99.

## Problemas resolvidos e limites

- O formatter JSON rejeitava service duplicado entre campo global e logging fluente.
  Removido o campo repetido; os cenários BDD passaram após a correção.
- O receptor OTLP do Agent em Docker falhava ao registrar métricas de processo com /host/proc.
  Aplicado o workaround oficial HOST_PROC=/proc; health check também exige listener 4318 disponível.
- Não há DD_API_KEY real configurada. Autenticação, ingestão SaaS, indexação, retenção e consultas na
  conta Datadog não foram validadas. Configure .env local conforme o guia de infraestrutura.
- At-least-once continua explícito: uma confirmação perdida entre Kafka e PostgreSQL permite
  reentrega; eventId estável e Inbox persistente continuam necessários.
- Não foram criados dashboards, monitores ou instrumentação dos fluxos restantes.

## Reproduzir

~~~powershell
mvn clean verify
./tests/integration/idempotency-bdd.ps1
./tests/integration/datadog-agent.ps1 -Otlp
./tests/integration/datadog-metrics.ps1 -Build
~~~

O BDD reconstrói as imagens AOT. Os testes usam somente recursos temporários próprios e
não removem os volumes compartilhados.

Referências: [ADR-0009](adr/0009-journey-otel-otlp.md),
[guia da jornada](observability.md) e [operação Datadog](../infrastructure/datadog/README.md).
