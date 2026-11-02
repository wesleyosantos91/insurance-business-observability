# ADR-0007 — Testes rápidos, BDD real e telemetria isolada

Status: Aceito. Data: 2026-11-02.

## Contexto

O build cotidiano não deve exigir um broker externo. Correção transacional e wire format Avro
exigem testes com os componentes reais; mocks não comprovam locks, commits ou deduplicação.

## Decisão

Manter testes unitários/contrato/contexto no build padrão. H2 serve apenas ao contexto que não executa
as migrações PostgreSQL, sem alegar equivalência de semântica SQL.
Usar profile integration com Cucumber, Failsafe e Testcontainers: Kafka, Schema Registry,
dois PostgreSQL e três aplicações AOT (duas réplicas de propostas).

Verificar repetição HTTP, conflito, concorrência, restart, broker pausado, duplicação, rollback,
offsets, framing Confluent e desserialização Avro. Não usar Docker de outro projeto como infraestrutura de teste.

Para telemetria, usar receptor DogStatsD/HTTP local sem dependências globais.
Verificar as seis identidades, métricas HTTP/JVM e AOT. Separar essa evidência da entrega SaaS.

## Alternativas consideradas

Embedded Kafka ou mocks isolados não cobrem Registry e semântica PostgreSQL.
Exigir chave Datadog para o build impediria testes reprodutíveis e exporia credenciais desnecessariamente.

## Consequências

BDD é mais lento e requer Docker/imagens. Testes funcionais concorrentes não são benchmark.
Falhas de infraestrutura devem ser reportadas, não convertidas em sucesso.
Os relatórios gerados ficam em target, fora do Git. Registros Markdown devem identificar
quando, o que foi executado e o que continua pendente.

## Validação

mvn clean verify, tests/integration/idempotency-bdd.ps1 e tests/integration/datadog-metrics.ps1.
Não declarar ingestão no Datadog sem evidência da conta correspondente.
