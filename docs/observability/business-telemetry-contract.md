# Contrato de telemetria de negócio — propostas

Este contrato descreve os sinais operacionais de ms-propostas e ms-cadastro. Micrometer e OTLP
são a fronteira de instrumentação; não há dependência proprietária Datadog na feature.

## Métricas canônicas

| Nome no payload OTLP | Tipo | Etapa / resultado | Momento e semântica |
|---|---|---|---|
| insurance.business.transitions | Counter / Sum delta | proposal_register, success | +1 somente depois do commit de uma proposta e Outbox novas |
| insurance.business.transitions | Counter / Sum delta | proposal_receive, success | +1 somente depois do retorno do proxy transacional com efeito Inbox novo |
| insurance.business.step.duration | Timer / Histogram | proposal_register ou proposal_receive; outcome e error_code | Uma amostra por execução da observação, inclusive replay, duplicata e falha; não mede a espera HTTP inteira pelo ACK |
| insurance.outbox.publish.attempts | Counter / Sum delta | outbox_publish | +1 por tentativa iniciada sobre uma claim obtida, incluindo recuperação; não conta propostas adicionais |
| insurance.outbox.publish.duration | Timer / Histogram | outbox_publish; outcome e error_code | Tempo da observação do relay, incluindo espera limitada do ACK e atualização/retry no banco; nenhuma conexão JDBC fica retida durante a espera de rede |
| insurance.inbox.duplicates | Counter / Sum delta | proposal_receive | +1 após reconhecer uma Inbox já existente e compatível; não incrementa transições |

Filtre step=proposal_register para contar criações observadas. Somar as etapas register e receive
conta dois marcos da mesma proposta; não representa duas propostas. Métricas são best effort,
não um ledger: crash após commit e antes do incremento pode perder uma medição. Replay não
reconta o efeito para compensar essa lacuna. Não há promessa exactly-once para métricas.

Os nomes acima são verificados no protobuf recebido pelo teste de integração. Timers Micrometer
exportam histogramas OTLP com unidade do registry; o teste registra a unidade efetiva. Não se
presume sufixo Prometheus _total nem se confunde .count de histograma com transições. No SaaS US5 validado na Etapa 10, os cinco nomes canônicos foram preservados sem sufixos.
Counters são count, timers são distribution em milissegundos. Use sum:<counter>{...}.as_count()
e avg:<timer>{...}; count:<timer>{...}.as_count() conta execuções, não transições.
Percentis estão desabilitados na conta; não usar p95/p99 sem revisão de custo e autorização. Cada ponto carrega service/env/version
ou seus resource attributes equivalentes. A temporalidade configurada é delta.

## Outcomes e falhas

- Registro: created, replayed, rejected ou technical_failure no timer/diagnóstico de registro.
- rejected/idempotency_conflict significa rejeição da requisição por reutilização de chave com
  outro conteúdo. Não significa rejeição de risco da proposta; esse fluxo ainda não existe.
- Relay: pending durante a tentativa; published, retry, claim_lost ou database_failure ao terminar.
- Inbox: new ou duplicate depois do commit; technical_failure em validação, processamento ou ACK.
- error_code: none, idempotency_conflict, registration_failure, publication_unconfirmed,
  lease_lost, persistence_failure, invalid_event, processing_failure ou acknowledgment_failure.

Pendente não é um evento de negócio novo. Use também insurance.outbox.pending e
insurance.outbox.oldest.age para backlog atual; attempts é cumulativo, não backlog.
Não há evento real de aprovação/rejeição de negócio nesta etapa; não inventamos essa transição.

Os contadores anteriores insurance.proposal.registrations, insurance.outbox.dispatches,
insurance.proposal.receipts e insurance.proposal.consume.failures continuam como diagnósticos.
Não os some aos contadores canônicos. Os nomes de spans permanecem insurance.proposal.register,
insurance.outbox.publish e insurance.proposal.consume; um MeterFilter apenas padroniza os timers,
sem criar segunda observação ou segundo span. O filtro remove error (classe de exceção) e
eventType dos timers canônicos. error_code permanece normalizado.

## Cardinalidade

Dimensões customizadas são journey_type=PROPOSAL, step, outcome e error_code de vocabulário
controlado. service, env e version identificam o deploy. Métricas nativas HTTP/JVM/Kafka/Hikari
mantêm suas convenções. Nunca usar proposalId/businessId, journeyId, rootJourneyId, eventId,
correlationId, traceId, spanId, chave de idempotência, mensagem de exceção ou productKey arbitrário
como dimensões de métricas. Não é necessário expor /actuator/metrics publicamente para exportar.

Backlog/idade lidos em réplicas do mesmo banco devem ser agregados por máximo, não por soma.
Counters/timers podem ser agregados entre instâncias, preservando etapa e identidade do deploy.

## Campos de logs e spans

| Conceito | JSON stdout | Atributo de span | Origem |
|---|---|---|---|
| Identidade de negócio | business_id / proposal_id | businessId / proposalId | Mesmo proposalId existente; alias, sem gerar outro ID |
| Jornada | journey_id | journeyId | ID persistido da jornada |
| Jornada raiz | root_journey_id | rootJourneyId | journeyId no registro atual; metadata no consumo |
| Evento | event_id | eventId | Mesmo eventId em todas as tentativas |
| Correlação | correlation_id | correlationId | Identificador persistido/Avro |
| Etapa | step | stepKey | proposal_register, outbox_publish ou proposal_receive |
| Resultado | outcome / error_code | status, outcome, error_code | Decisão observada; não altera o contrato de domínio |
| Produto | product_key | productKey | Campo Avro existente; logs e spans, nunca tag arbitrária de métrica |
| Tipo da jornada | journey_type | journey_type | PROPOSAL, controlado; Datadog normaliza tag de métrica para proposal |
| Trace / span | trace_id / span_id | Contexto OTel | Hexadecimal lowercase de 32 / 16 caracteres |
| Deploy | service, environment, env, version | Resource service.name, deployment.environment.name, service.version | Configuração do serviço |

JSON Logstash nativo inclui @timestamp e level. Os logs normais de registro, confirmação Kafka,
consumo e duplicata têm os IDs conhecidos. Falhas de claim ou de validação anterior à extração
não possuem necessariamente IDs confiáveis: omitir é preferível a inventar valores.
O JSON padroniza campos em snake_case; DTO HTTP e Avro preservam seus nomes existentes.

Não há businessId, stepKey ou status novos no Avro: são projeções locais da telemetria. Não são
necessárias migrações ou alteração de schema. parentJourneyId/causationId opcionais continuam
no contrato; não há nova jornada que exija preenchê-los nesta etapa.

## Correlação e limites

journeyId é identidade de negócio, independente de traceId. Um replay HTTP ou uma reentrega
sem contexto pode produzir outro trace com os mesmos IDs de negócio. traceId não substitui
journeyId. Spans de aplicações diferentes têm spanId próprios.

Contexto W3C original é persistido na Outbox e reaberto pelo relay depois do commit da claim.
KafkaTemplate e listener propagam/extraiem os headers nativamente. Não entram no fingerprint
nem em dados pessoais. Escopos Micrometer limpam o MDC ao fechar; callback Kafka reabre apenas
o escopo necessário ao log, sem compartilhar mapas mutáveis entre threads.

O contexto persistido é um parent remoto causal. Span links foram avaliados: não são necessários
para um único evento/parent nesta etapa. Fan-in ou política de iniciar traces novos após grande
atraso pode justificar links depois. Filas prolongadas, sampling e retenção podem mostrar traces
parciais; correlacione também pelos IDs de negócio e pelo banco operacional.

[Operação Datadog](datadog-integration.md) · [ADR-0012](../adr/0012-business-telemetry-contract.md).
