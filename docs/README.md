# Documentação

## Começar e operar

- [README principal](../README.md): stack, inicialização e contrato HTTP.
- [Kafka KRaft, Schema Registry e Avro](../infrastructure/kafka/README.md).
- [PostgreSQL por serviço](../infrastructure/persistence/README.md).
- [Datadog, credenciais locais e diagnóstico](../infrastructure/datadog/README.md).
- [Docker, Spring AOT e JVM AOT Cache](containers.md).
- [Idempotência, Outbox/Inbox e concorrência](idempotency-testing.md).
- [Testes de integração](../tests/integration/README.md).
- [Contratos gerados](../libraries/event-contracts/README.md).
- [ADRs arquiteturais](adr/README.md).

- [Resiliência do Outbox Relay](outbox-relay.md): lease, fencing, backoff e jitter.

- [Observabilidade da jornada com OTLP](observability.md).

## Evidências

- [Validação da Etapa 9](observability-validation.md).

- [Validação de resiliência do relay](outbox-relay-validation.md).

- [Validação histórica de Datadog/Java Agent e AOT](datadog-validation.md).
- [Validação de idempotência](idempotency-validation.md): execução anterior à instrumentação Datadog.
- [Integração Java/Kafka inicial](kafka-java-integration.md): etapa anterior à Outbox/Inbox.
- [Validação Java/Kafka inicial](kafka-java-validation.md): evidência histórica, não contagem atual de testes.
- [Validação Kafka](../infrastructure/kafka/VALIDATION.md).

Documentos históricos preservam os resultados da respectiva etapa. Para operar o estado atual,
priorize o README principal, os ADRs e os documentos de idempotência/Datadog.
